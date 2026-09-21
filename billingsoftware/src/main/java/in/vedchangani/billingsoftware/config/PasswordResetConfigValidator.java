package in.vedchangani.billingsoftware.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fails startup when password-reset configuration is unusable, so a misconfiguration is found at
 * deploy time and not when a customer is locked out:
 * <ul>
 *   <li>always: the dedicated OTP HMAC secret must be present and at least {@value #MIN_SECRET_LENGTH}
 *       characters, and the numeric limits must be sensible;</li>
 *   <li>in production (Spring profile "prod" or "production"): SMTP host and the from address must be
 *       set, because a production app must not silently run with reset mail disabled.</li>
 * </ul>
 * Local development and tests may run without SMTP; the mail service then just skips sending.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetConfigValidator implements InitializingBean {

    static final int MIN_SECRET_LENGTH = 32;
    private static final Profiles PRODUCTION = Profiles.of("prod", "production");

    private final PasswordResetProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        validate(properties, environment.acceptsProfiles(PRODUCTION), environment.getProperty("spring.mail.host"));
    }

    // Static so the rules can be unit-tested without a Spring context. Messages name the variable
    // only - never a value.
    static void validate(PasswordResetProperties p, boolean production, String mailHost) {
        if (!StringUtils.hasText(p.getOtpSecret()) || p.getOtpSecret().trim().length() < MIN_SECRET_LENGTH) {
            throw misconfigured("APP_PASSWORD_RESET_OTP_SECRET must be set to a random secret of at least "
                    + MIN_SECRET_LENGTH + " characters (and must not be the JWT secret)");
        }
        if (p.getOtpTtl() == null || p.getOtpTtl().isZero() || p.getOtpTtl().isNegative()) {
            throw misconfigured("app.password-reset.otp-ttl must be positive");
        }
        if (p.getResendCooldown() == null || p.getResendCooldown().isNegative()) {
            throw misconfigured("app.password-reset.resend-cooldown must not be negative");
        }
        if (p.getMaxAttempts() < 1 || p.getMaxSendsPerHour() < 1) {
            throw misconfigured("app.password-reset.max-attempts and max-sends-per-hour must be at least 1");
        }
        if (p.getIpWindow() == null || p.getIpWindow().isZero() || p.getIpWindow().isNegative()
                || p.getRequestIpLimit() < 1 || p.getResetIpLimit() < 1) {
            throw misconfigured("app.password-reset ip-window and IP limits must be positive");
        }
        if (production) {
            if (!StringUtils.hasText(mailHost)) {
                throw misconfigured("MAIL_HOST is required in production: password-reset mail must not be disabled");
            }
            if (!StringUtils.hasText(p.getMailFrom())) {
                throw misconfigured("APP_MAIL_FROM is required in production");
            }
        }
    }

    private static IllegalStateException misconfigured(String message) {
        return new IllegalStateException("Password-reset configuration invalid: " + message);
    }
}
