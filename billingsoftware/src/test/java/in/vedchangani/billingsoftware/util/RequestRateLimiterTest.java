package in.vedchangani.billingsoftware.util;

import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class RequestRateLimiterTest {

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void allowsUpToTheLimitThenBlocksUntilTheWindowExpires() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock);
        Duration window = Duration.ofMinutes(15);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip:1", 3, window));
        }
        assertFalse(limiter.tryAcquire("ip:1", 3, window));
        assertTrue(limiter.tryAcquire("ip:2", 3, window), "other keys are independent");

        clock.advance(Duration.ofMinutes(15));
        assertTrue(limiter.tryAcquire("ip:1", 3, window), "a new window starts");
    }

    @Test
    void expiredWindowsAreSweptAutomatically() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock);
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("ip:" + i, 5, Duration.ofMinutes(1));
        }
        assertEquals(100, limiter.trackedKeys());
        clock.advance(Duration.ofMinutes(6)); // past both the window and the sweep interval
        limiter.tryAcquire("fresh", 5, Duration.ofMinutes(1));
        assertEquals(1, limiter.trackedKeys());
    }

    private static HttpServletRequest request(String remote, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        for (String value : forwardedFor) {
            request.addHeader("X-Forwarded-For", value);
        }
        return request;
    }

    @Test
    void forwardedForIsIgnoredUnlessProxyTrustIsEnabled() {
        ClientIpResolver resolver = new ClientIpResolver(new PasswordResetProperties());
        assertEquals("10.0.0.5", resolver.resolve(request("10.0.0.5", "6.6.6.6")));
    }

    @Test
    void behindATrustedProxyOnlyTheProxyAppendedLastEntryIsUsed() {
        PasswordResetProperties properties = new PasswordResetProperties();
        properties.setTrustForwardedFor(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);
        // a client-supplied 1.1.1.1 is followed by the address nginx actually saw
        assertEquals("203.0.113.9", resolver.resolve(request("172.18.0.3", "1.1.1.1, 203.0.113.9")));
        assertEquals("203.0.113.9", resolver.resolve(request("172.18.0.3", "1.1.1.1", "203.0.113.9")));
        assertEquals("172.18.0.3", resolver.resolve(request("172.18.0.3")), "no header -> peer address");
    }
}
