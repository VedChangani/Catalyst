package in.vedchangani.billingsoftware.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Customer forgot-password / OTP settings, bound from app.password-reset.* (see application.properties
 * for the environment variables behind them). Checked at startup by {@link PasswordResetConfigValidator}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.password-reset")
public class PasswordResetProperties {

    // Dedicated HMAC key for stored code hashes. Never the JWT secret, never printed.
    @ToString.Exclude
    private String otpSecret;
    private Duration otpTtl = Duration.ofMinutes(10);
    private int maxAttempts = 5;
    private Duration resendCooldown = Duration.ofSeconds(60);
    private int maxSendsPerHour = 5;
    // From address of the reset mail, e.g. "Catalyst <no-reply@your-domain>".
    private String mailFrom;

    // Per-IP limits of the public endpoints (instance-local, in memory).
    private Duration ipWindow = Duration.ofMinutes(15);
    private int requestIpLimit = 10;
    private int resetIpLimit = 20;
    // Honour X-Forwarded-For only behind a trusted proxy (see ClientIpResolver).
    private boolean trustForwardedFor = false;
}
