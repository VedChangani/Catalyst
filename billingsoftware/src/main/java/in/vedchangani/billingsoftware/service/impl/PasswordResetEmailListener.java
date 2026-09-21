package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.event.PasswordResetOtpIssuedEvent;
import in.vedchangani.billingsoftware.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the code only once the transaction that stored it has committed (so no mail goes out for a
 * rolled-back request), and on a background thread (so the response time does not depend on SMTP
 * and cannot reveal whether an account existed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetEmailListener {

    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOtpIssued(PasswordResetOtpIssuedEvent event) {
        try {
            emailService.sendPasswordResetOtp(event.email(), event.otp(), event.validFor());
        } catch (RuntimeException e) {
            // Class name only: neither the address nor the code may reach the log.
            log.warn("Password-reset email could not be sent ({})", e.getClass().getSimpleName());
        }
    }
}
