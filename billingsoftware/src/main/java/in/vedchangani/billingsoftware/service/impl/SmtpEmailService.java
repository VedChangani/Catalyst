package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import in.vedchangani.billingsoftware.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpEmailService implements EmailService {

    static final String SUBJECT = "Your Catalyst password reset code";
    private static final String DEV_FROM = "Catalyst <no-reply@localhost>";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final PasswordResetProperties properties;

    @Override
    public void sendPasswordResetOtp(String toEmail, String otp, Duration validFor) {
        JavaMailSender sender = mailSender.getIfAvailable();
        boolean configured = sender != null
                && !(sender instanceof JavaMailSenderImpl impl && !StringUtils.hasText(impl.getHost()));
        if (!configured) {
            // Only reachable outside production (production refuses to start without SMTP). No address
            // and no code in the message.
            log.warn("Password-reset email not sent: SMTP is not configured (set MAIL_HOST)");
            return;
        }
        long minutes = Math.max(1, validFor.toMinutes());
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(StringUtils.hasText(properties.getMailFrom()) ? properties.getMailFrom() : DEV_FROM);
            helper.setTo(toEmail);
            helper.setSubject(SUBJECT);
            helper.setText(plainTextBody(otp, minutes), htmlBody(otp, minutes));
            sender.send(message);
        } catch (MessagingException e) {
            // Not chained: the cause's message could carry the address.
            throw new IllegalStateException("Could not build the password-reset email");
        }
    }

    static String plainTextBody(String otp, long minutes) {
        return "Catalyst - password reset\n\n"
                + "Your verification code is: " + otp + "\n\n"
                + "It expires in " + minutes + " minutes and can be used once.\n\n"
                + "Never share this code with anyone. Catalyst will never ask for it.\n"
                + "If you did not request a password reset, you can ignore this email - your password "
                + "has not been changed.\n";
    }

    static String htmlBody(String otp, long minutes) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto;"
                + "border:2px solid #111827;padding:24px;color:#111827\">"
                + "<h2 style=\"margin:0 0 4px\">Catalyst</h2>"
                + "<p style=\"margin:0 0 20px;color:#6b7280\">Password reset</p>"
                + "<p>Use this verification code to reset your password:</p>"
                + "<p style=\"font-size:32px;font-weight:bold;letter-spacing:8px;margin:16px 0\">" + otp + "</p>"
                + "<p>It expires in <strong>" + minutes + " minutes</strong> and can be used once.</p>"
                + "<p style=\"font-size:13px;color:#6b7280\">Never share this code with anyone - Catalyst will "
                + "never ask for it. If you did not request a password reset, ignore this email; your "
                + "password has not been changed.</p></div>";
    }
}
