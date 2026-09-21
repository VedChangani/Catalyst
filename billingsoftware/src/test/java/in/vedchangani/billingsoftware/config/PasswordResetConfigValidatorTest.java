package in.vedchangani.billingsoftware.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PasswordResetConfigValidatorTest {

    private static PasswordResetProperties valid() {
        PasswordResetProperties p = new PasswordResetProperties();
        p.setOtpSecret("x".repeat(40));
        p.setMailFrom("Catalyst <no-reply@example.com>");
        return p;
    }

    @Test
    void defaultsMatchTheApprovedOtpRules() {
        PasswordResetProperties p = new PasswordResetProperties();
        assertEquals(Duration.ofMinutes(10), p.getOtpTtl());
        assertEquals(5, p.getMaxAttempts());
        assertEquals(Duration.ofSeconds(60), p.getResendCooldown());
        assertEquals(5, p.getMaxSendsPerHour());
        assertFalse(p.isTrustForwardedFor());
    }

    @Test
    void theOtpSecretIsRequiredAndMustNotBeTrivial() {
        for (String bad : new String[]{null, "", "   ", "short-secret", "y".repeat(31)}) {
            PasswordResetProperties p = valid();
            p.setOtpSecret(bad);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> PasswordResetConfigValidator.validate(p, false, null));
            assertTrue(e.getMessage().contains("APP_PASSWORD_RESET_OTP_SECRET"));
        }
        assertDoesNotThrow(() -> PasswordResetConfigValidator.validate(valid(), false, null));
    }

    @Test
    void localDevelopmentMayRunWithoutSmtp() {
        PasswordResetProperties p = valid();
        p.setMailFrom(null);
        assertDoesNotThrow(() -> PasswordResetConfigValidator.validate(p, false, ""));
    }

    @Test
    void productionRefusesToStartWithoutMailConfiguration() {
        assertThrows(IllegalStateException.class, () -> PasswordResetConfigValidator.validate(valid(), true, null));
        assertThrows(IllegalStateException.class, () -> PasswordResetConfigValidator.validate(valid(), true, "  "));
        PasswordResetProperties noFrom = valid();
        noFrom.setMailFrom("");
        assertThrows(IllegalStateException.class,
                () -> PasswordResetConfigValidator.validate(noFrom, true, "smtp.example.com"));
        assertDoesNotThrow(() -> PasswordResetConfigValidator.validate(valid(), true, "smtp.example.com"));
    }

    @Test
    void theErrorNeverContainsTheSecretValue() {
        PasswordResetProperties p = valid();
        p.setOtpSecret("tooshort");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PasswordResetConfigValidator.validate(p, false, null));
        assertFalse(e.getMessage().contains("tooshort"));
        assertFalse(p.toString().contains("tooshort"));
    }
}
