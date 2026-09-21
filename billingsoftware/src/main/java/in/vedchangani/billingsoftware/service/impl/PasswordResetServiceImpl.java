package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import in.vedchangani.billingsoftware.entity.PasswordResetOtpEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.event.PasswordResetOtpIssuedEvent;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.repository.PasswordResetOtpRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.PasswordResetService;
import in.vedchangani.billingsoftware.util.ContactNormalizer;
import in.vedchangani.billingsoftware.util.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Transactions are programmatic on purpose: a wrong code must COMMIT its attempt increment and only
 * afterwards be reported as an error. Throwing from inside a @Transactional method would roll the
 * increment back and make the attempt limit useless.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final String CUSTOMER_ROLE = "ROLE_USER";

    private final UserRepository userRepository;
    private final PasswordResetOtpRepository otpRepository;
    private final PasswordResetOtpCodec codec;
    private final PasswordResetProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // ---- request a code ----

    @Override
    public void requestReset(String rawEmail) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    findEligibleCustomer(rawEmail).ifPresent(this::issueCode));
        } catch (DataIntegrityViolationException e) {
            // Two first-time requests raced to create the account's row (unique user_id): the other
            // one issued the code, so this one is simply skipped - same silent outcome as a cooldown.
        }
    }

    private void issueCode(UserEntity user) {
        LocalDateTime now = LocalDateTime.now(clock);
        // Locks the existing row (if any) so concurrent requests for one account are serialized.
        PasswordResetOtpEntity row = otpRepository.findByUserIdForUpdate(user.getId()).orElse(null);
        if (row == null) {
            row = new PasswordResetOtpEntity();
            row.setUser(user);
        } else {
            if (row.getLastSentAt().plus(properties.getResendCooldown()).isAfter(now)) {
                return; // resend cooldown: silently no new code
            }
            boolean windowOpen = row.getSendWindowStart() != null
                    && row.getSendWindowStart().plusHours(1).isAfter(now);
            if (windowOpen && row.getSendCount() >= properties.getMaxSendsPerHour()) {
                return; // hourly cap reached
            }
        }
        boolean windowOpen = row.getSendWindowStart() != null && row.getSendWindowStart().plusHours(1).isAfter(now);
        if (!windowOpen) {
            row.setSendWindowStart(now);
            row.setSendCount(0);
        }
        row.setSendCount(row.getSendCount() + 1);

        String otp = codec.generate();
        // Overwriting the row is what invalidates the previous code.
        row.setOtpHash(codec.hash(user.getUserId(), otp));
        row.setExpiresAt(now.plus(properties.getOtpTtl()));
        row.setAttempts(0);
        row.setConsumedAt(null);
        row.setLastSentAt(now);
        otpRepository.saveAndFlush(row);

        // Handled only after this transaction commits (see PasswordResetEmailListener).
        eventPublisher.publishEvent(new PasswordResetOtpIssuedEvent(user.getEmail(), otp, properties.getOtpTtl()));
    }

    // ---- reset with a code ----

    private enum Outcome {RESET, REJECTED}

    @Override
    public void resetPassword(String rawEmail, String otp, String newPassword, String confirmNewPassword) {
        // Input problems that say nothing about the account and do not consume an attempt.
        if (newPassword == null || !newPassword.equals(confirmNewPassword)) {
            throw new IllegalArgumentException("New password and confirmation do not match");
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            throw new IllegalArgumentException(PasswordPolicy.MESSAGE);
        }
        Outcome outcome = transactionTemplate.execute(status -> verifyAndReset(rawEmail, otp, newPassword));
        if (outcome != Outcome.RESET) {
            // Thrown AFTER the transaction committed, so a wrong guess's attempt increment is kept.
            throw new IllegalArgumentException(INVALID_CODE_MESSAGE);
        }
    }

    private Outcome verifyAndReset(String rawEmail, String otp, String newPassword) {
        UserEntity user = findEligibleCustomer(rawEmail).orElse(null);
        if (user == null || otp == null) {
            return Outcome.REJECTED;
        }
        PasswordResetOtpEntity row = otpRepository.findByUserIdForUpdate(user.getId()).orElse(null);
        if (row == null) {
            return Outcome.REJECTED;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (row.getConsumedAt() != null
                || !row.getExpiresAt().isAfter(now)
                || row.getAttempts() >= properties.getMaxAttempts()) {
            return Outcome.REJECTED;
        }
        if (!codec.matches(user.getUserId(), otp, row.getOtpHash())) {
            otpRepository.incrementAttempts(row.getId());
            return Outcome.REJECTED;
        }
        // Belt and braces next to the row lock: only one caller can flip consumed_at from NULL.
        if (otpRepository.consume(row.getId(), now) != 1) {
            return Outcome.REJECTED;
        }
        // BCrypt hash + tokenVersion bump in one UPDATE: every earlier session is revoked.
        userRepository.updatePasswordAndRevokeTokens(user.getId(), passwordEncoder.encode(newPassword));
        // The account itself is the actor (nobody is authenticated). No code, password or hash.
        auditService.recordFor(user, AuditAction.PASSWORD_RESET_COMPLETED, AuditTargetType.ACCOUNT,
                user.getUserId(), Map.of("method", "EMAIL_OTP"));
        return Outcome.RESET;
    }

    // ---- shared ----

    // Only enabled ROLE_USER accounts, found by email alone (same normalization and legacy-case
    // fallback as login). Anything else is treated exactly like "no such account".
    private Optional<UserEntity> findEligibleCustomer(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return Optional.empty();
        }
        String email = ContactNormalizer.normalizeEmail(rawEmail);
        Optional<UserEntity> user = userRepository.findByEmail(email);
        if (user.isEmpty() && !email.equals(rawEmail.trim())) {
            user = userRepository.findByEmail(rawEmail.trim());
        }
        return user.filter(u -> CUSTOMER_ROLE.equals(u.getRole()) && u.isAccountEnabled());
    }
}
