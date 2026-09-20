package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customer self-registration (POST /register) and email-or-mobile login (POST /login) through the
 * real SecurityConfig filter chain, controllers, services, BCrypt encoder, JWT and H2 repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerRegistrationAndLoginTest {

    private static final String PASSWORD = "Secret123";
    private static final String GENERIC_LOGIN_ERROR = "Email/mobile or password is incorrect";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // Unique valid Indian mobile per call, so tests sharing the H2 database never collide.
    private static String newMobile() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private String email(String prefix) {
        return prefix + "-" + suffix + "@example.com";
    }

    private String registrationBody(String name, String email, String mobile, String password, String extraJson) {
        StringBuilder body = new StringBuilder("{");
        body.append("\"name\":").append(name == null ? "null" : "\"" + name + "\"");
        body.append(",\"email\":").append(email == null ? "null" : "\"" + email + "\"");
        body.append(",\"mobile\":").append(mobile == null ? "null" : "\"" + mobile + "\"");
        body.append(",\"password\":").append(password == null ? "null" : "\"" + password + "\"");
        if (extraJson != null) {
            body.append(",").append(extraJson);
        }
        return body.append("}").toString();
    }

    private MvcResult register(String body) throws Exception {
        return mockMvc.perform(post("/register").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult login(String identifier, String password) throws Exception {
        String body = "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}";
        return mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UserEntity seedLegacyAccount(String email, String role) {
        // Accounts that existed before A1: no mobile, provisioned directly with a BCrypt hash.
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).name("Legacy " + role)
                .password(passwordEncoder.encode(PASSWORD)).role(role).build());
    }

    // ---- Registration ----

    @Test
    void anonymousRegistration_createsCustomerWithHashedPassword() throws Exception {
        String email = email("new");
        String mobile = newMobile();

        MvcResult result = register(registrationBody("  New Customer ", email, mobile, PASSWORD, null));

        assertEquals(201, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("ROLE_USER", body.get("role").asText());
        assertEquals(email, body.get("email").asText());
        assertEquals(mobile, body.get("mobile").asText());
        assertEquals("New Customer", body.get("name").asText());
        assertFalse(body.has("password"));
        assertFalse(result.getResponse().getContentAsString().contains(PASSWORD));

        UserEntity saved = userRepository.findByEmail(email).orElseThrow();
        assertEquals("ROLE_USER", saved.getRole());
        assertNotEquals(PASSWORD, saved.getPassword());
        assertTrue(saved.getPassword().startsWith("$2"), "password must be stored as a BCrypt hash");
        assertTrue(passwordEncoder.matches(PASSWORD, saved.getPassword()));
        assertFalse(result.getResponse().getContentAsString().contains(saved.getPassword()));
    }

    @Test
    void registration_normalizesEmailAndMobile() throws Exception {
        String mobile = newMobile();
        String typedMobile = "+91 " + mobile.substring(0, 5) + "-" + mobile.substring(5);

        MvcResult result = register(registrationBody("Norm", "  Norm-" + suffix + "@Example.COM ", typedMobile, PASSWORD, null));

        assertEquals(201, result.getResponse().getStatus());
        UserEntity saved = userRepository.findByMobile(mobile).orElseThrow();
        assertEquals("norm-" + suffix + "@example.com", saved.getEmail());
        assertEquals(mobile, saved.getMobile());
    }

    @Test
    void suppliedAdminRole_isIgnored_accountIsAlwaysCustomer() throws Exception {
        String email = email("wants-admin");
        MvcResult result = register(registrationBody("Mallory", email, newMobile(), PASSWORD,
                "\"role\":\"ROLE_ADMIN\",\"authorities\":[\"ROLE_ADMIN\"],\"admin\":true,\"enabled\":true"));

        assertEquals(201, result.getResponse().getStatus());
        assertEquals("ROLE_USER", json(result).get("role").asText());
        assertEquals("ROLE_USER", userRepository.findByEmail(email).orElseThrow().getRole());
    }

    @Test
    void suppliedCashierRole_isIgnored_accountIsAlwaysCustomer() throws Exception {
        String email = email("wants-cashier");
        MvcResult result = register(registrationBody("Mallory", email, newMobile(), PASSWORD, "\"role\":\"ROLE_CASHIER\""));

        assertEquals(201, result.getResponse().getStatus());
        assertEquals("ROLE_USER", userRepository.findByEmail(email).orElseThrow().getRole());
    }

    @Test
    void duplicateEmail_isRejected_andExistingAccountIsUntouched() throws Exception {
        String email = email("dup");
        UserEntity existing = seedLegacyAccount(email, "ROLE_CASHIER");

        // Same email in a different case/spacing is still the same account.
        MvcResult result = register(registrationBody("Imposter", " " + email.toUpperCase() + " ", newMobile(), "Other123", null));

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("An account with this email already exists", json(result).get("message").asText());
        UserEntity after = userRepository.findByEmail(email).orElseThrow();
        assertEquals(existing.getPassword(), after.getPassword());
        assertEquals("ROLE_CASHIER", after.getRole());
        assertEquals(existing.getName(), after.getName());
        assertNull(after.getMobile());
    }

    @Test
    void duplicateMobile_isRejected_evenInADifferentFormat() throws Exception {
        String mobile = newMobile();
        assertEquals(201, register(registrationBody("First", email("m1"), mobile, PASSWORD, null)).getResponse().getStatus());

        MvcResult result = register(registrationBody("Second", email("m2"), "+91" + mobile, PASSWORD, null));

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("An account with this mobile number already exists", json(result).get("message").asText());
        assertTrue(userRepository.findByEmail(email("m2")).isEmpty());
    }

    @Test
    void invalidEmail_isRejected() throws Exception {
        MvcResult result = register(registrationBody("Bad", "not-an-email", newMobile(), PASSWORD, null));
        assertEquals(400, result.getResponse().getStatus());
    }

    @Test
    void invalidMobile_isRejected() throws Exception {
        for (String mobile : new String[]{"12345", "1234567890", "98765abcde", "+1 2025550123"}) {
            MvcResult result = register(registrationBody("Bad", email("badmobile"), mobile, PASSWORD, null));
            assertEquals(400, result.getResponse().getStatus(), "mobile " + mobile);
        }
        assertTrue(userRepository.findByEmail(email("badmobile")).isEmpty());
    }

    @Test
    void missingRequiredFields_areRejected() throws Exception {
        assertEquals(400, register(registrationBody(null, email("a"), newMobile(), PASSWORD, null)).getResponse().getStatus());
        assertEquals(400, register(registrationBody("  ", email("b"), newMobile(), PASSWORD, null)).getResponse().getStatus());
        assertEquals(400, register(registrationBody("N", null, newMobile(), PASSWORD, null)).getResponse().getStatus());
        assertEquals(400, register(registrationBody("N", email("c"), null, PASSWORD, null)).getResponse().getStatus());
        assertEquals(400, register(registrationBody("N", email("d"), newMobile(), null, null)).getResponse().getStatus());
    }

    @Test
    void weakPassword_isRejected() throws Exception {
        assertEquals(400, register(registrationBody("N", email("w1"), newMobile(), "short1", null)).getResponse().getStatus());
        assertEquals(400, register(registrationBody("N", email("w2"), newMobile(), "onlyletters", null)).getResponse().getStatus());
        assertEquals(400, register(registrationBody("N", email("w3"), newMobile(), "12345678", null)).getResponse().getStatus());
    }

    @Test
    void authenticatedUser_cannotEscalateThroughRegistration() throws Exception {
        String userEmail = email("signed-in");
        seedLegacyAccount(userEmail, "ROLE_USER");
        String token = json(login(userEmail, PASSWORD)).get("token").asText();

        String newEmail = email("second");
        MvcResult result = mockMvc.perform(post("/register")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("Me Again", newEmail, newMobile(), PASSWORD, "\"role\":\"ROLE_ADMIN\"")))
                .andReturn();

        assertEquals(201, result.getResponse().getStatus());
        assertEquals("ROLE_USER", userRepository.findByEmail(newEmail).orElseThrow().getRole());
        // the caller's own account is unchanged
        assertEquals("ROLE_USER", userRepository.findByEmail(userEmail).orElseThrow().getRole());
        // and the caller still cannot reach staff/admin endpoints
        mockMvc.perform(get("/admin/cashiers").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void anonymous_stillCannotUseAdminRegistration() throws Exception {
        mockMvc.perform(post("/admin/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"n\",\"email\":\"" + email("anon-admin") + "\",\"password\":\"secret1\",\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isUnauthorized());
        assertTrue(userRepository.findByEmail(email("anon-admin")).isEmpty());
    }

    // ---- Login ----

    @Test
    void registeredCustomer_canLogInByEmail_andByMobile() throws Exception {
        String email = email("login");
        String mobile = newMobile();
        register(registrationBody("Login Customer", email, mobile, PASSWORD, null));

        MvcResult byEmail = login("  " + email.toUpperCase() + " ", PASSWORD);
        assertEquals(200, byEmail.getResponse().getStatus());
        assertEquals("ROLE_USER", json(byEmail).get("role").asText());
        assertEquals(email, json(byEmail).get("email").asText());

        for (String typed : new String[]{mobile, "+91 " + mobile, "0" + mobile}) {
            MvcResult byMobile = login(typed, PASSWORD);
            assertEquals(200, byMobile.getResponse().getStatus(), "mobile " + typed);
            JsonNode body = json(byMobile);
            assertEquals("ROLE_USER", body.get("role").asText());
            // The JWT subject is still the account email - the established username.
            assertEquals(email, jwtUtil.extractUsername(body.get("token").asText()));
        }
    }

    @Test
    void loginAcceptsTheLegacyEmailField() throws Exception {
        String email = email("legacy-field");
        seedLegacyAccount(email, "ROLE_USER");

        mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_USER"));
    }

    @Test
    void wrongPassword_andUnknownIdentifiers_failWithTheSameGenericError() throws Exception {
        String email = email("known");
        String mobile = newMobile();
        register(registrationBody("Known", email, mobile, PASSWORD, null));

        MvcResult[] failures = {
                login(email, "Wrong1234"),
                login(mobile, "Wrong1234"),
                login(email("nobody"), PASSWORD),
                login(newMobile(), PASSWORD),
                login("not-a-real-identifier", PASSWORD)
        };
        for (MvcResult failure : failures) {
            assertEquals(401, failure.getResponse().getStatus());
            assertEquals(GENERIC_LOGIN_ERROR, json(failure).get("message").asText());
            assertFalse(json(failure).has("token"));
        }
    }

    @Test
    void existingAdminCashierAndUserAccounts_stillLogIn_withTheirRoles() throws Exception {
        for (String role : new String[]{"ROLE_ADMIN", "ROLE_CASHIER", "ROLE_USER"}) {
            String email = email(role.toLowerCase());
            seedLegacyAccount(email, role);

            MvcResult result = login(email, PASSWORD);
            assertEquals(200, result.getResponse().getStatus(), role);
            assertEquals(role, json(result).get("role").asText());
            assertEquals(email, jwtUtil.extractUsername(json(result).get("token").asText()));
        }
    }

    @Test
    void legacyMixedCaseEmail_canStillLogIn() throws Exception {
        String stored = "Mixed-" + suffix + "@Example.com";
        seedLegacyAccount(stored, "ROLE_ADMIN");

        MvcResult result = login(stored, PASSWORD);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("ROLE_ADMIN", json(result).get("role").asText());
    }

    @Test
    void issuedJwt_authenticatesWithTheAccountsRoleAuthority() throws Exception {
        String customerEmail = email("jwt-user");
        String mobile = newMobile();
        register(registrationBody("Jwt User", customerEmail, mobile, PASSWORD, null));
        String adminEmail = email("jwt-admin");
        seedLegacyAccount(adminEmail, "ROLE_ADMIN");
        String cashierEmail = email("jwt-cashier");
        seedLegacyAccount(cashierEmail, "ROLE_CASHIER");

        String customerToken = json(login(mobile, PASSWORD)).get("token").asText();
        String adminToken = json(login(adminEmail, PASSWORD)).get("token").asText();
        String cashierToken = json(login(cashierEmail, PASSWORD)).get("token").asText();

        // ROLE_USER: own-history endpoint yes, admin/POS no
        mockMvc.perform(get("/orders/my-orders").header("Authorization", "Bearer " + customerToken)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/cashiers").header("Authorization", "Bearer " + customerToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/pos/customers").param("search", "ab").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        // ROLE_CASHIER: POS yes, admin no
        mockMvc.perform(get("/pos/customers").param("search", "ab").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/cashiers").header("Authorization", "Bearer " + cashierToken)).andExpect(status().isForbidden());
        // ROLE_ADMIN: admin yes
        mockMvc.perform(get("/admin/cashiers").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    }

    @Test
    void authResponsesAndTokens_neverContainPasswordsHashesOrContactDetails() throws Exception {
        String email = email("secret");
        String mobile = newMobile();
        MvcResult registration = register(registrationBody("Secret Keeper", email, mobile, PASSWORD, null));
        MvcResult login = login(mobile, PASSWORD);
        String hash = userRepository.findByEmail(email).orElseThrow().getPassword();

        for (MvcResult result : new MvcResult[]{registration, login}) {
            String content = result.getResponse().getContentAsString();
            assertFalse(content.contains(PASSWORD));
            assertFalse(content.contains(hash));
            assertFalse(content.toLowerCase().contains("\"password\""));
        }
        assertFalse(json(login).has("password"));

        String token = json(login).get("token").asText();
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertFalse(payload.contains(PASSWORD));
        assertFalse(payload.contains(hash));
        assertFalse(payload.contains(mobile));
        assertFalse(payload.toLowerCase().contains("password"));
    }
}
