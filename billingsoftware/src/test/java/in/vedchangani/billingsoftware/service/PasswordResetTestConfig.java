package in.vedchangani.billingsoftware.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared by the password-reset tests (imported, so they share one Spring context): a capturing
 * fake EmailService instead of SMTP, and an inline executor so the after-commit @Async send is
 * deterministic. Autowire this class itself to read what was "sent".
 */
@TestConfiguration
public class PasswordResetTestConfig {

    public record Sent(String to, String otp) {}

    public final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    EmailService capturingEmailService() {
        return (to, otp, validFor) -> sent.add(new Sent(to, otp));
    }

    // Named "taskExecutor" so @Async uses it instead of a thread pool.
    @Bean(name = "taskExecutor")
    SyncTaskExecutor taskExecutor() {
        return new SyncTaskExecutor();
    }
}
