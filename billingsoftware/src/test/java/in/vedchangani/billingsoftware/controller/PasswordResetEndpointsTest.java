package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.PasswordResetOtpEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.PasswordResetOtpRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.PasswordResetTestConfig;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /forgot-password and POST /reset-password through the real SecurityConfig, JWT filter,
 * controller, services and H2. Mail goes to the capturing fake (no SMTP). Each request gets its own
 * client IP so the per-IP limiter is only exercised where a test means to. Deliberately NOT
 * @Transactional so every service transaction really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(PasswordResetTestConfig.class)
class PasswordResetEndpointsTest {

    private static final String LEGACY_PASSWORD = "password123";
    private static final String NEW_PASSWORD = "Fresh#Pass1";
    private static final String GENERIC_202 =
            "If an eligible account exists for that email, a verification code has been sent.";
    private static final String GENERIC_400 = "The code is invalid or has expired.";
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordResetTestConfig mail;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetOtpRepository otpRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UserEntity customer;

    @BeforeEach
    void setUp() {
        customer = account("ROLE_USER", true);
        mail.sent.clear();
    }

    @AfterEach
    void tearDown() {
        otpRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity account(String role, boolean enabled) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID())
                .email("ep-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .name("Endpoint Tester").role(role).enabled(enabled)
                .mobile(TestMobiles.next()).password(passwordEncoder.encode(LEGACY_PASSWORD)).build());
    }

    private static String freshIp() {
        int n = IP_COUNTER.incrementAndGet();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body, String ip) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private MvcResult forgot(String email, String ip) throws Exception {
        return mockMvc.perform(json(post("/forgot-password"), "{\"email\":\"" + email + "\"}", ip)).andReturn();
    }

    private MvcResult forgot(String email) throws Exception {
        return forgot(email, freshIp());
    }

    private MvcResult reset(String email, String otp, String password, String confirm, String ip) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"otp\":\"" + otp + "\",\"newPassword\":\"" + password
                + "\",\"confirmNewPassword\":\"" + confirm + "\"}";
        return mockMvc.perform(json(post("/reset-password"), body, ip)).andReturn();
    }

    private MvcResult reset(String email, String otp) throws Exception {
        return reset(email, otp, NEW_PASSWORD, NEW_PASSWORD, freshIp());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private void assertGeneric202(MvcResult result) throws Exception {
        assertEquals(202, status(result));
        assertEquals(GENERIC_202, body(result).get("message").asText());
    }

    private void assertGeneric400(MvcResult result) throws Exception {
        assertEquals(400, status(result), result.getResponse().getContentAsString());
        JsonNode json = body(result);
        assertEquals(GENERIC_400, json.get("message").asText());
        assertEquals(400, json.get("status").asInt());
        assertNotNull(json.get("timestamp"));
        assertNotNull(json.get("path"));
    }

    private String otpFor(UserEntity user) {
        for (int i = mail.sent.size() - 1; i >= 0; i--) {
            if (mail.sent.get(i).to().equals(user.getEmail())) {
                return mail.sent.get(i).otp();
            }
        }
        return null;
    }

    private long mailsTo(UserEntity user) {
        return mail.sent.stream().filter(s -> s.to().equals(user.getEmail())).count();
    }

    /** Requests a code for the customer and returns it (read from the fake mailbox). */
    private String issueCode(UserEntity user) throws Exception {
        assertGeneric202(forgot(user.getEmail()));
        return otpFor(user);
    }

    private PasswordResetOtpEntity row(UserEntity user) {
        return otpRepository.findByUserId(user.getId()).orElse(null);
    }

    private UserEntity reload(UserEntity user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private String wrongCodeFor(String otp) {
        return otp.equals("000000") ? "000001" : "000000";
    }

    private void backdateLastSent(UserEntity user, Duration age) {
        jdbcTemplate.update("UPDATE tbl_password_reset_otp SET last_sent_at = ? WHERE user_id = ?",
                LocalDateTime.now().minus(age), user.getId());
    }

    private int loginStatus(String email, String password) throws Exception {
        return status(mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn());
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
        assertEquals(200, status(result));
        return body(result).get("token").asText();
    }

    // ================= POST /forgot-password =================

    @Test
    void eligibleCustomerGetsTheGenericAnswerAndACodeIsEmailed() throws Exception {
        MvcResult result = forgot(customer.getEmail());

        assertGeneric202(result);
        assertEquals(1, mailsTo(customer));
        assertTrue(otpFor(customer).matches("\\d{6}"));
        assertNotNull(row(customer));
        assertFalse(result.getResponse().getContentAsString().contains(otpFor(customer)), "never in the response");
    }

    @Test
    void unknownEmailGetsTheIdenticalAnswerAndNoCode() throws Exception {
        MvcResult known = forgot(customer.getEmail());
        MvcResult unknown = forgot("nobody-" + UUID.randomUUID() + "@example.com");

        assertGeneric202(unknown);
        assertEquals(body(known).get("message"), body(unknown).get("message"));
        assertEquals(1, mail.sent.size(), "only the real customer was mailed");
    }

    @Test
    void cashierAdminAndDisabledCustomerGetTheIdenticalAnswerAndNoCode() throws Exception {
        UserEntity cashier = account("ROLE_CASHIER", true);
        UserEntity admin = account("ROLE_ADMIN", true);
        UserEntity disabled = account("ROLE_USER", false);

        for (UserEntity ineligible : List.of(cashier, admin, disabled)) {
            assertGeneric202(forgot(ineligible.getEmail()));
            assertNull(row(ineligible), "no OTP row for " + ineligible.getRole() + "/" + ineligible.getEnabled());
            assertEquals(0, mailsTo(ineligible));
        }
    }

    @Test
    void theResendCooldownStaysSilent() throws Exception {
        String first = issueCode(customer);
        String hash = row(customer).getOtpHash();

        assertGeneric202(forgot(customer.getEmail())); // straight away again

        assertEquals(1, mailsTo(customer));
        assertEquals(hash, row(customer).getOtpHash(), "no new code inside 60 seconds");
        assertEquals(200, status(reset(customer.getEmail(), first)), "the first code still works");
    }

    @Test
    void theHourlyCapStaysSilent() throws Exception {
        issueCode(customer);
        for (int i = 0; i < 4; i++) {
            backdateLastSent(customer, Duration.ofSeconds(61));
            assertGeneric202(forgot(customer.getEmail()));
        }
        assertEquals(5, mailsTo(customer));

        backdateLastSent(customer, Duration.ofSeconds(61));
        assertGeneric202(forgot(customer.getEmail())); // sixth within the hour

        assertEquals(5, mailsTo(customer), "no additional code, same response");
    }

    @Test
    void aMalformedRequestIsAValidationError() throws Exception {
        for (String badBody : new String[]{"{\"email\":\"not-an-email\"}", "{\"email\":\"\"}", "{}", "not json"}) {
            MvcResult result = mockMvc.perform(json(post("/forgot-password"), badBody, freshIp())).andReturn();
            assertEquals(400, status(result), badBody);
            assertNotNull(body(result).get("message"));
        }
        assertTrue(mail.sent.isEmpty());
    }

    @Test
    void bothEndpointsAreReachableWithoutAJwt() throws Exception {
        assertEquals(202, status(forgot(customer.getEmail())));
        // a real reset attempt reaches the service (400), it is not turned away with 401/403
        assertEquals(400, status(reset(customer.getEmail(), "123456")));
    }

    @Test
    void anExcessOfRequestsFromOneIpGets429WhetherOrNotTheAccountExists() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 10; i++) {
            String email = i % 2 == 0 ? customer.getEmail() : "ghost-" + i + "@example.com";
            assertEquals(202, status(forgot(email, ip)), "request " + (i + 1));
        }
        for (String email : new String[]{customer.getEmail(), "ghost-x@example.com"}) {
            MvcResult limited = forgot(email, ip);
            assertEquals(429, status(limited));
            JsonNode json = body(limited);
            assertEquals("Too many requests. Please try again later.", json.get("message").asText());
            assertEquals(429, json.get("status").asInt());
        }
        assertEquals(202, status(forgot(customer.getEmail(), freshIp())), "another IP is unaffected");
    }

    @Test
    void theResetEndpointHasItsOwnPerIpLimit() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 20; i++) {
            assertEquals(400, status(reset("ghost@example.com", "123456", NEW_PASSWORD, NEW_PASSWORD, ip)));
        }
        MvcResult limited = reset("ghost@example.com", "123456", NEW_PASSWORD, NEW_PASSWORD, ip);
        assertEquals(429, status(limited));
        assertEquals("Too many requests. Please try again later.", body(limited).get("message").asText());
    }

    // ================= POST /reset-password =================

    @Test
    void aValidCodeAndStrongPasswordResetsThePassword() throws Exception {
        String otp = issueCode(customer);

        MvcResult result = reset(customer.getEmail(), otp);

        assertEquals(200, status(result));
        assertEquals("Password updated. Please sign in.", body(result).get("message").asText());
        assertFalse(result.getResponse().getContentAsString().contains("token"), "no automatic login");
        assertEquals(401, loginStatus(customer.getEmail(), LEGACY_PASSWORD), "old password fails");
        assertEquals(200, loginStatus(customer.getEmail(), NEW_PASSWORD), "new password works through /login");
    }

    @Test
    void successBumpsTokenVersionExactlyOnceAndRevokesTheOldJwt() throws Exception {
        String oldJwt = loginToken(customer.getEmail(), LEGACY_PASSWORD);
        assertEquals(200, status(mockMvc.perform(get("/account/me").header("Authorization", "Bearer " + oldJwt)).andReturn()));
        int before = reload(customer).currentTokenVersion();
        String otp = issueCode(customer);

        assertEquals(200, status(reset(customer.getEmail(), otp)));

        assertEquals(before + 1, reload(customer).currentTokenVersion());
        assertEquals(401, status(mockMvc.perform(get("/account/me").header("Authorization", "Bearer " + oldJwt)).andReturn()),
                "the JWT issued before the reset is dead");
        assertEquals(before + 1, reload(customer).currentTokenVersion(), "and nothing bumped it a second time");
    }

    @Test
    void anUnknownEmailGetsTheGenericFailure() throws Exception {
        assertGeneric400(reset("nobody-" + UUID.randomUUID() + "@example.com", "123456"));
    }

    @Test
    void aWrongCodeGetsTheGenericFailureAndTheAttemptIsCommitted() throws Exception {
        String otp = issueCode(customer);

        assertGeneric400(reset(customer.getEmail(), wrongCodeFor(otp)));

        assertEquals(1, row(customer).getAttempts());
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(customer).getPassword()));
        assertEquals(200, status(reset(customer.getEmail(), otp)), "one wrong guess does not burn the code");
    }

    @Test
    void fiveWrongAttemptsLockTheCodeAndTheSixthWithTheCorrectCodeFails() throws Exception {
        String otp = issueCode(customer);
        for (int i = 1; i <= 5; i++) {
            assertGeneric400(reset(customer.getEmail(), wrongCodeFor(otp)));
            assertEquals(i, row(customer).getAttempts());
        }

        assertGeneric400(reset(customer.getEmail(), otp));

        assertEquals(5, row(customer).getAttempts());
        assertNull(row(customer).getConsumedAt());
        assertEquals(200, loginStatus(customer.getEmail(), LEGACY_PASSWORD), "password untouched");

        // a fresh code, after the cooldown, works again
        backdateLastSent(customer, Duration.ofSeconds(61));
        String fresh = issueCode(customer);
        assertEquals(200, status(reset(customer.getEmail(), fresh)));
    }

    @Test
    void anExpiredCodeGetsTheGenericFailure() throws Exception {
        String otp = issueCode(customer);
        jdbcTemplate.update("UPDATE tbl_password_reset_otp SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusSeconds(1), customer.getId());

        assertGeneric400(reset(customer.getEmail(), otp));
        assertEquals(200, loginStatus(customer.getEmail(), LEGACY_PASSWORD));
    }

    @Test
    void aUsedCodeCannotBeReused() throws Exception {
        String otp = issueCode(customer);
        assertEquals(200, status(reset(customer.getEmail(), otp)));
        int version = reload(customer).currentTokenVersion();

        assertGeneric400(reset(customer.getEmail(), otp));

        assertEquals(version, reload(customer).currentTokenVersion());
    }

    @Test
    void aMismatchedConfirmationIsAValidationErrorAndKeepsTheCodeUsable() throws Exception {
        String otp = issueCode(customer);

        MvcResult mismatch = reset(customer.getEmail(), otp, NEW_PASSWORD, NEW_PASSWORD + "x", freshIp());

        assertEquals(400, status(mismatch));
        assertTrue(body(mismatch).get("message").asText().contains("do not match"));
        assertNotEquals(GENERIC_400, body(mismatch).get("message").asText());
        assertEquals(0, row(customer).getAttempts(), "no attempt used");
        assertNull(row(customer).getConsumedAt(), "code not consumed");
        assertEquals(200, status(reset(customer.getEmail(), otp)), "correcting the confirmation works");
    }

    @Test
    void weakPasswordsAreRejectedWithoutUsingUpTheCode() throws Exception {
        String otp = issueCode(customer);
        for (String weak : new String[]{"password123", "PASSWORD123!", "Password!!", "Pass1!", "Password123"}) {
            MvcResult result = reset(customer.getEmail(), otp, weak, weak, freshIp());
            assertEquals(400, status(result), weak);
            assertTrue(body(result).get("message").asText().contains("Password must be 8-72"), weak);
        }
        for (String badCode : new String[]{"12345", "1234567", "12345a", ""}) {
            assertEquals(400, status(reset(customer.getEmail(), badCode, NEW_PASSWORD, NEW_PASSWORD, freshIp())));
        }
        assertEquals(0, row(customer).getAttempts());
        assertNull(row(customer).getConsumedAt());
        assertEquals(200, status(reset(customer.getEmail(), otp)), "a strong password is accepted");
    }

    @Test
    void aCodeIssuedForOneCustomerCannotResetAnother() throws Exception {
        UserEntity other = account("ROLE_USER", true);
        String customersOtp = issueCode(customer);
        String othersOtp = issueCode(other);

        if (!customersOtp.equals(othersOtp)) { // (1 in a million they collide)
            assertGeneric400(reset(other.getEmail(), customersOtp));
        }
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(other).getPassword()));
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(customer).getPassword()));
    }

    @Test
    void clientSuppliedIdentityFieldsAreIgnored() throws Exception {
        UserEntity other = account("ROLE_USER", true);
        String customersOtp = issueCode(customer);
        String body = "{\"email\":\"" + other.getEmail() + "\",\"otp\":\"" + customersOtp + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\",\"confirmNewPassword\":\"" + NEW_PASSWORD + "\",\"userId\":\""
                + customer.getUserId() + "\",\"role\":\"ROLE_ADMIN\",\"mobile\":\"" + customer.getMobile() + "\"}";

        MvcResult result = mockMvc.perform(json(post("/reset-password"), body, freshIp())).andReturn();

        assertEquals(400, status(result));
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(customer).getPassword()));
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(other).getPassword()));
        assertEquals("ROLE_USER", reload(other).getRole());
    }

    @Test
    void cashierAndAdminCannotResetEvenWithAPlantedValidCode() throws Exception {
        for (String role : new String[]{"ROLE_CASHIER", "ROLE_ADMIN"}) {
            UserEntity staff = account(role, true);
            assertGeneric202(forgot(staff.getEmail()));
            assertNull(row(staff), "no OTP row is ever created for " + role);

            // even if a row somehow existed, the reset is refused
            PasswordResetOtpEntity planted = new PasswordResetOtpEntity();
            planted.setUser(staff);
            planted.setOtpHash(hashFor(staff, "123456"));
            planted.setExpiresAt(LocalDateTime.now().plusMinutes(5));
            planted.setLastSentAt(LocalDateTime.now());
            otpRepository.save(planted);

            assertGeneric400(reset(staff.getEmail(), "123456"));
            assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(staff).getPassword()), role);
            assertEquals(0, reload(staff).currentTokenVersion());
        }
    }

    @Autowired private in.vedchangani.billingsoftware.service.impl.PasswordResetOtpCodec codec;

    private String hashFor(UserEntity user, String otp) {
        return codec.hash(user.getUserId(), otp);
    }

    @Test
    void aDisabledCustomerCannotResetEvenWithAValidCode() throws Exception {
        String otp = issueCode(customer);
        jdbcTemplate.update("UPDATE tbl_users SET enabled = FALSE WHERE id = ?", customer.getId());

        assertGeneric400(reset(customer.getEmail(), otp));
        assertTrue(passwordEncoder.matches(LEGACY_PASSWORD, reload(customer).getPassword()));
    }

    @Test
    void concurrentUseOfTheSameCodeSucceedsExactlyOnce() throws Exception {
        String otp = issueCode(customer);
        int version = reload(customer).currentTokenVersion();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String ip = freshIp();
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                int code = status(reset(customer.getEmail(), otp, NEW_PASSWORD, NEW_PASSWORD, ip));
                if (code == 200) ok.incrementAndGet();
                else if (code == 400) rejected.incrementAndGet();
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, ok.get(), "exactly one request wins");
        assertEquals(threads - 1, rejected.get());
        assertEquals(version + 1, reload(customer).currentTokenVersion(), "the password changed once");
    }

    // ================= secrets =================

    @Test
    void theCodeNeverAppearsInResponsesLogsOrAuditOutput(CapturedOutput output) throws Exception {
        MvcResult requested = forgot(customer.getEmail());
        String otp = otpFor(customer);
        MvcResult wrong = reset(customer.getEmail(), wrongCodeFor(otp));
        MvcResult success = reset(customer.getEmail(), otp);
        MvcResult reused = reset(customer.getEmail(), otp);

        for (MvcResult result : new MvcResult[]{requested, wrong, success, reused}) {
            String responseBody = result.getResponse().getContentAsString();
            assertFalse(responseBody.contains(otp), responseBody);
            assertFalse(responseBody.contains(NEW_PASSWORD), responseBody);
        }
        assertFalse(output.getAll().contains(otp), "the code must not be logged");
        assertFalse(output.getAll().contains(NEW_PASSWORD), "the password must not be logged");

        List<String> audit = jdbcTemplate.queryForList(
                "SELECT CONCAT(COALESCE(details, ''), '|', COALESCE(target_id, '')) FROM tbl_audit_log "
                        + "WHERE actor_public_id = ?", String.class, customer.getUserId());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tbl_audit_log WHERE action = 'PASSWORD_RESET_COMPLETED' AND actor_public_id = ?",
                Integer.class, customer.getUserId()), "one audit event, only for the success");
        for (String entry : audit) {
            assertFalse(entry.contains(otp));
            assertFalse(entry.contains(NEW_PASSWORD));
            assertFalse(entry.toLowerCase().contains("token"));
        }
    }
}
