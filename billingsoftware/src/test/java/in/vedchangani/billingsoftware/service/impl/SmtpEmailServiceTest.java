package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class SmtpEmailServiceTest {

    private static final class CapturingSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();

        @Override
        public void send(MimeMessage mimeMessage) {
            sent.add(mimeMessage);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    private static PasswordResetProperties props() {
        PasswordResetProperties p = new PasswordResetProperties();
        p.setMailFrom("Catalyst <no-reply@example.com>");
        return p;
    }

    @Test
    void theEmailHasBrandingCodeExpiryAndSecurityNoticeButNoPassword() throws Exception {
        CapturingSender sender = new CapturingSender();
        sender.setHost("smtp.example.com");
        sender.setSession(Session.getInstance(new Properties()));
        new SmtpEmailService(provider(sender), props())
                .sendPasswordResetOtp("customer@example.com", "482913", Duration.ofMinutes(10));

        assertEquals(1, sender.sent.size());
        MimeMessage message = sender.sent.get(0);
        assertEquals("customer@example.com", message.getAllRecipients()[0].toString());
        assertTrue(message.getFrom()[0].toString().contains("no-reply@example.com"));
        assertEquals(SmtpEmailService.SUBJECT, message.getSubject());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        assertTrue(out.toString().contains("482913"));

        String text = SmtpEmailService.plainTextBody("482913", 10);
        assertTrue(text.contains("Catalyst") && text.contains("482913") && text.contains("10 minutes"));
        assertTrue(text.contains("Never share this code"));
        assertTrue(SmtpEmailService.htmlBody("482913", 10).contains("Catalyst"));
    }

    @Test
    void withoutSmtpConfigurationNothingIsSentAndNothingFails() {
        assertDoesNotThrow(() -> new SmtpEmailService(provider(null), props())
                .sendPasswordResetOtp("a@example.com", "123456", Duration.ofMinutes(10)));

        // Spring creates a sender with an empty host when MAIL_HOST is unset
        CapturingSender blankHost = new CapturingSender();
        assertDoesNotThrow(() -> new SmtpEmailService(provider(blankHost), props())
                .sendPasswordResetOtp("a@example.com", "123456", Duration.ofMinutes(10)));
        assertTrue(blankHost.sent.isEmpty());
    }
}
