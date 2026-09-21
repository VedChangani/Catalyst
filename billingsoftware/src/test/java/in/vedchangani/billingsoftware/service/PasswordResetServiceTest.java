package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.PasswordResetOtpEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.PasswordResetOtpRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.PasswordResetOtpCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Backend foundation of the customer forgot-password flow, against the real services, JPA
 * repositories and H2 (there are no public endpoints yet - those come in a later batch). Mail goes
 * to a capturing fake, and @Async runs inline so the after-commit send is deterministic.
 * Not @Transactional: every service transaction really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(PasswordResetTestConfig.class)
class PasswordResetServiceTest {

    private static final String GOOD_PASSWORD = "Fresh#Pass1";
    private static final String LEGACY_PASSWORD = "password123"; // valid under the OLD policy only

    @Autowired private PasswordResetTestConfig fakes;
    @Autowired private PasswordResetService service;
    @Autowired private PasswordResetOtpRepository otpRepository;
    @Autowired private PasswordResetOtpCodec codec;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private Environment environment;

    private UserEntity customer;

    @BeforeEach
    void setUp() {
        customer = account("ROLE_USER", true, LEGACY_PASSWORD);
        fakes.sent.clear();
    }

    @AfterEach
    void tearDown() {
        otpRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity account(String role, boolean enabled, String rawPassword) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID())
                .email("reset-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .name("Reset Tester").role(role).enabled(enabled)
                .mobile(TestMobiles.next()).password(passwordEncoder.encode(rawPassword)).build());
    }

    private UserEntity reload(UserEntity user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private PasswordResetOtpEntity row(UserEntity user) {
        return otpRepository.findByUserId(user.getId()).orElse(null);
    }

    private String lastOtpFor(UserEntity user) {
        for (int i = fakes.sent.size() - 1; i >= 0; i--) {
            if (fakes.sent.get(i).to().equals(user.getEmail())) {
                return fakes.sent.get(i).otp();
            }
        }
        return null;
    }

    private long sentTo(UserEntity user) {
        return fakes.sent.stream().filter(s -> s.to().equals(user.getEmail())).count();
    }

    private String request(UserEntity user) {
        service.requestReset(user.getEmail());
        return lastOtpFor(user);
    }

    private void reset(UserEntity user, String otp) {
        service.resetPassword(user.getEmail(), otp, GOOD_PASSWORD, GOOD_PASSWORD);
    }

    private void assertRejected(UserEntity user, String otp) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> reset(user, otp));
        assertEquals(PasswordResetService.INVALID_CODE_MESSAGE, e.getMessage());
    }

    private String wrongCodeFor(String otp) {
        return otp.equals("000000") ? "000001" : "000000";
    }

    private void backdate(UserEntity user, Duration age) {
        jdbcTemplate.update("UPDATE tbl_password_reset_otp SET last_sent_at = ? WHERE user_id = ?",
                LocalDateTime.now().minus(age), user.getId());
    }

    private int loginStatus(String email, String password) throws Exception {
        return mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    // ---- issuing a code ----

    @Test
    void anEligibleCustomerGetsASixDigitCodeAndOnlyItsHmacIsStored() {
        String otp = request(customer);

        assertNotNull(otp);
        assertTrue(otp.matches("\\d{6}"));
        assertEquals(1, sentTo(customer));
        PasswordResetOtpEntity stored = row(customer);
        assertNotEquals(otp, stored.getOtpHash());
        assertFalse(stored.getOtpHash().contains(otp));
        assertEquals(codec.hash(customer.getUserId(), otp), stored.getOtpHash());
        assertEquals(0, stored.getAttempts());
        assertNull(stored.getConsumedAt());
        // 10-minute expiry
        Duration ttl = Duration.between(stored.getLastSentAt(), stored.getExpiresAt());
        assertEquals(Duration.ofMinutes(10), ttl);
    }

    @Test
    void unknownCashierAdminAndDisabledAccountsGetNoCodeAndNoSignal() {
        UserEntity cashier = account("ROLE_CASHIER", true, LEGACY_PASSWORD);
        UserEntity admin = account("ROLE_ADMIN", true, LEGACY_PASSWORD);
        UserEntity disabled = account("ROLE_USER", false, LEGACY_PASSWORD);

        assertDoesNotThrow(() -> service.requestReset("nobody-" + UUID.randomUUID() + "@example.com"));
        for (UserEntity ineligible : List.of(cashier, admin, disabled)) {
            assertDoesNotThrow(() -> service.requestReset(ineligible.getEmail()));
            assertNull(row(ineligible), "no OTP row for " + ineligible.getRole());
            assertEquals(0, sentTo(ineligible));
            assertRejected(ineligible, "123456");
        }
        assertDoesNotThrow(() -> service.requestReset(null));
        assertDoesNotThrow(() -> service.requestReset("  "));
        assertTrue(fakes.sent.isEmpty());
    }

    @Test
    void theEmailIsMatchedCaseInsensitivelyLikeLogin() {
        service.requestReset("  " + customer.getEmail().toUpperCase() + " ");
        assertEquals(1, sentTo(customer));
    }

    @Test
    void resendWithinTheCooldownIsSilentlyIgnored() {
        String first = request(customer);
        String hashBefore = row(customer).getOtpHash();

        service.requestReset(customer.getEmail()); // immediately again

        assertEquals(1, sentTo(customer), "no second mail inside 60 seconds");
        assertEquals(hashBefore, row(customer).getOtpHash());
        assertDoesNotThrow(() -> reset(customer, first), "the first code is still the valid one");
    }

    @Test
    void aResendAfterTheCooldownReplacesTheOldCode() {
        String first = request(customer);
        backdate(customer, Duration.ofSeconds(61));

        String second = request(customer);

        assertEquals(2, sentTo(customer));
        assertEquals(codec.hash(customer.getUserId(), second), row(customer).getOtpHash());
        if (!first.equals(second)) { // (1 in a million: the new random code equals the old one)
            assertRejected(customer, first);
        }
        assertDoesNotThrow(() -> reset(customer, second));
    }

    @Test
    void theHourlySendCapStopsFurtherCodesUntilTheWindowPasses() {
        request(customer);
        for (int i = 0; i < 4; i++) {
            backdate(customer, Duration.ofSeconds(61));
            service.requestReset(customer.getEmail());
        }
        assertEquals(5, sentTo(customer));
        assertEquals(5, row(customer).getSendCount());

        backdate(customer, Duration.ofSeconds(61));
        service.requestReset(customer.getEmail()); // 6th within the hour
        assertEquals(5, sentTo(customer), "cap reached: silently no mail");

        // the window itself ages out
        jdbcTemplate.update("UPDATE tbl_password_reset_otp SET send_window_start = ?, last_sent_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(61), LocalDateTime.now().minusMinutes(61), customer.getId());
        service.requestReset(customer.getEmail());
        assertEquals(6, sentTo(customer));
        assertEquals(1, row(customer).getSendCount());
    }

    @Test
    void theEmailIsSentOnlyAfterTheTransactionCommits() {
        transactionTemplate.executeWithoutResult(status -> {
            service.requestReset(customer.getEmail()); // joins this outer transaction
            assertEquals(0, sentTo(customer), "not sent while the transaction is still open");
        });
        assertEquals(1, sentTo(customer), "sent once it committed");

        UserEntity other = account("ROLE_USER", true, LEGACY_PASSWORD);
        transactionTemplate.executeWithoutResult(status -> {
            service.requestReset(other.getEmail());
            status.setRollbackOnly();
        });
        assertEquals(0, sentTo(other), "a rolled-back request sends nothing");
        assertNull(row(other));
    }

    // ---- verifying / resetting ----

    @Test
    void aValidCodeResetsThePasswordBumpsTokenVersionAndRecordsOneAuditEvent() throws Exception {
        String otp = request(customer);
        int versionBefore = reload(customer).currentTokenVersion();

        reset(customer, otp);

        UserEntity after = reload(customer);
        assertEquals(versionBefore + 1, after.currentTokenVersion(), "every existing JWT is revoked");
        assertTrue(passwordEncoder.matches(GOOD_PASSWORD, after.getPassword()));
        assertFalse(passwordEncoder.matches(LEGACY_PASSWORD, after.getPassword()));
        assertNotNull(row(customer).getConsumedAt());
        assertEquals(200, loginStatus(customer.getEmail(), GOOD_PASSWORD));
        assertEquals(401, loginStatus(customer.getEmail(), LEGACY_PASSWORD), "old password no longer works");

        List<String> details = jdbcTemplate.queryForList(
                "SELECT details FROM tbl_audit_log WHERE action = 'PASSWORD_RESET_COMPLETED' AND actor_public_id = ?",
                String.class, customer.getUserId());
        assertEquals(1, details.size());
        assertFalse(details.get(0).contains(otp));
        assertFalse(details.get(0).contains(GOOD_PASSWORD));
    }

    @Test
    void aCodeCannotBeUsedTwice() {
        String otp = request(customer);
        reset(customer, otp);
        int versionAfterFirst = reload(customer).currentTokenVersion();

        assertRejected(customer, otp);
        assertEquals(versionAfterFirst, reload(customer).currentTokenVersion());
    }

    @Test
    void aWrongCodeIsRejectedAndTheAttemptIsCommitted() {
        String otp = request(customer);
        String passwordHash = reload(customer).getPassword();

        assertRejected(customer, wrongCodeFor(otp));

        assertEquals(1, row(customer).getAttempts(), "the increment survives the failed request");
        assertEquals(passwordHash, reload(customer).getPassword());
        assertDoesNotThrow(() -> reset(customer, otp), "one wrong guess does not burn the code");
    }

    @Test
    void afterFiveFailedAttemptsEvenTheCorrectCodeIsRefused() {
        String otp = request(customer);
        for (int i = 0; i < 5; i++) {
            assertRejected(customer, wrongCodeFor(otp));
        }
        assertEquals(5, row(customer).getAttempts());

        assertRejected(customer, otp);
        assertEquals(5, row(customer).getAttempts(), "a locked-out code is not incremented further");
        assertNull(row(customer).getConsumedAt());
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(customer).getPassword()));

        // a fresh code (after the cooldown) starts clean
        backdate(customer, Duration.ofSeconds(61));
        String fresh = request(customer);
        assertEquals(0, row(customer).getAttempts());
        assertDoesNotThrow(() -> reset(customer, fresh));
    }

    @Test
    void anExpiredCodeIsRefused() {
        String otp = request(customer);
        jdbcTemplate.update("UPDATE tbl_password_reset_otp SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusSeconds(1), customer.getId());

        assertRejected(customer, otp);
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(customer).getPassword()));
    }

    @Test
    void aCodeIssuedForOneCustomerNeverWorksForAnother() {
        UserEntity other = account("ROLE_USER", true, LEGACY_PASSWORD);
        String customersOtp = request(customer);
        String othersOtp = request(other);
        assertNotEquals(row(customer).getOtpHash(), row(other).getOtpHash());

        // the attacker knows their own code and someone else's email
        if (!customersOtp.equals(othersOtp)) {
            assertRejected(other, customersOtp);
        }
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(other).getPassword()));
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(customer).getPassword()));
    }

    @Test
    void aStaffAccountCannotBeResetEvenIfARowExists() {
        UserEntity cashier = account("ROLE_CASHIER", true, LEGACY_PASSWORD);
        PasswordResetOtpEntity planted = new PasswordResetOtpEntity();
        planted.setUser(cashier);
        planted.setOtpHash(codec.hash(cashier.getUserId(), "123456"));
        planted.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        planted.setLastSentAt(LocalDateTime.now());
        otpRepository.save(planted);

        assertRejected(cashier, "123456");
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(cashier).getPassword()));
    }

    @Test
    void aDisabledCustomerCannotResetEvenWithAValidCode() {
        String otp = request(customer);
        jdbcTemplate.update("UPDATE tbl_users SET enabled = FALSE WHERE id = ?", customer.getId());

        assertRejected(customer, otp);
    }

    @Test
    void weakPasswordsAndMismatchesAreRejectedWithoutUsingUpAnAttempt() {
        String otp = request(customer);

        for (String weak : new String[]{"password123", "PASSWORD123!", "Password!!", "Pass1!", "Password123"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.resetPassword(customer.getEmail(), otp, weak, weak));
            assertTrue(e.getMessage().startsWith("Password must be 8-72"), weak);
        }
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword(customer.getEmail(), otp, GOOD_PASSWORD, GOOD_PASSWORD + "x"));
        assertTrue(mismatch.getMessage().contains("do not match"));

        assertEquals(0, row(customer).getAttempts());
        assertNull(row(customer).getConsumedAt());
        assertDoesNotThrow(() -> reset(customer, otp), "the code is still usable afterwards");
    }

    @Test
    void twoConcurrentUsesOfTheSameCodeCannotBothSucceed() throws Exception {
        String otp = request(customer);
        int versionBefore = reload(customer).currentTokenVersion();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    reset(customer, otp);
                    successes.incrementAndGet();
                } catch (IllegalArgumentException expectedForTheLosers) {
                    // rejected
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertEquals(1, successes.get(), "exactly one concurrent use may win");
        assertEquals(versionBefore + 1, reload(customer).currentTokenVersion(), "the password changed once");
        assertNotNull(row(customer).getConsumedAt());
    }

    // ---- legacy passwords, logging, configuration ----

    @Test
    void aLegacyPasswordStillLogsInBecauseExistingHashesAreNeverTouched() throws Exception {
        assertFalse(in.vedchangani.billingsoftware.util.PasswordPolicy.isValid(LEGACY_PASSWORD));
        assertEquals(200, loginStatus(customer.getEmail(), LEGACY_PASSWORD));
        // requesting a reset changes nothing until a valid code is supplied
        request(customer);
        assertEquals(200, loginStatus(customer.getEmail(), LEGACY_PASSWORD));
    }

    @Test
    void theCodeNeverAppearsInApplicationLogs(CapturedOutput output) {
        String otp = request(customer);
        assertRejected(customer, wrongCodeFor(otp));
        reset(customer, otp);
        assertRejected(customer, otp);

        assertFalse(output.getAll().contains(otp), "the plaintext code must never be logged");
        assertFalse(output.getAll().contains(GOOD_PASSWORD));
        assertFalse(output.getAll().contains(row(customer).getOtpHash()));
    }

    @Test
    void smtpDebugIsDisabledSoMessageBodiesCannotBeLogged() {
        assertEquals("false", environment.getProperty("spring.mail.properties.mail.debug"));
    }
}
