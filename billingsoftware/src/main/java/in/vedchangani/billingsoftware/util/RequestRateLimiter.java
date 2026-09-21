package in.vedchangani.billingsoftware.util;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counter per key (e.g. "reset-request:1.2.3.4"), held in memory.
 *
 * INSTANCE-LOCAL: counters live in this JVM only, reset on restart and are not shared between
 * instances, so this is a first line of defence against bursts, not a distributed rate limiter.
 * The per-account limits stored in the database (cooldown, hourly cap, attempt counter) are the
 * authoritative protection for an account.
 *
 * Expired windows are swept automatically, so memory stays bounded by the number of distinct
 * keys seen within one window.
 */
@Component
public class RequestRateLimiter {

    private static final long SWEEP_INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int SWEEP_SIZE_THRESHOLD = 50_000;

    private static final class Window {
        final long expiresAtMillis;
        int count;

        Window(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long nextSweepMillis;

    public RequestRateLimiter(Clock clock) {
        this.clock = clock;
        this.nextSweepMillis = clock.millis() + SWEEP_INTERVAL_MILLIS;
    }

    /** Counts one hit for the key; false once more than {@code limit} hits fall in the current window. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        boolean[] allowed = {false};
        windows.compute(key, (k, current) -> {
            if (current == null || now >= current.expiresAtMillis) {
                allowed[0] = limit >= 1;
                Window fresh = new Window(now + window.toMillis());
                fresh.count = 1;
                return fresh;
            }
            if (current.count < limit) {
                current.count++;
                allowed[0] = true;
            }
            return current;
        });
        sweepIfDue(now);
        return allowed[0];
    }

    int trackedKeys() {
        return windows.size();
    }

    private void sweepIfDue(long now) {
        if (now >= nextSweepMillis || windows.size() > SWEEP_SIZE_THRESHOLD) {
            nextSweepMillis = now + SWEEP_INTERVAL_MILLIS;
            windows.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtMillis);
        }
    }
}
