package in.vedchangani.billingsoftware.service;

import java.time.Duration;

/**
 * Transactional email. The seam tests replace with a capturing fake; the real implementation sends
 * through Spring's JavaMailSender (SMTP configured entirely from the environment).
 */
public interface EmailService {

    /** Emails the one-time password-reset code. Implementations must never log the code. */
    void sendPasswordResetOtp(String toEmail, String otp, Duration validFor);
}
