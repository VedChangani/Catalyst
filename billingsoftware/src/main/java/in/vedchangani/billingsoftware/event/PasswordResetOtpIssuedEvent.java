package in.vedchangani.billingsoftware.event;

import java.time.Duration;

/**
 * Published inside the request transaction and handled only AFTER it commits. Carries the plaintext
 * code in memory just long enough to email it - it is never persisted, and toString is redacted so
 * it cannot reach a log.
 */
public record PasswordResetOtpIssuedEvent(String email, String otp, Duration validFor) {

    @Override
    public String toString() {
        return "PasswordResetOtpIssuedEvent[redacted]";
    }
}
