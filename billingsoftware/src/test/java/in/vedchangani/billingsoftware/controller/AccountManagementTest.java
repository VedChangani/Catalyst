package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A6: the caller's own account - GET/PATCH /account/me and PATCH /account/me/password - through the
 * real SecurityConfig, JWT filter (real bearer tokens), services and the H2 database.
 * Deliberately NOT @Transactional so every service transaction really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountManagementTest {

    private static final String PASSWORD = "Secret123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String s;
    private UserEntity customer;
    private UserEntity other;
    private UserEntity cashier;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        customer = account("Cara Customer", "cara-" + s + "@example.com", "ROLE_USER");
        other = account("Otto Other", "otto-" + s + "@example.com", "ROLE_USER");
        cashier = account("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        admin = account("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity account(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).name(name).role(role)
                .mobile(TestMobiles.next()).password(passwordEncoder.encode(PASSWORD)).build());
    }

    private UserEntity reload(UserEntity user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String login(String identifier, String password) throws Exception {
        MvcResult result = perform(json(post("/login"),
                "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("token").asText();
    }

    private int loginStatus(String identifier, String password) throws Exception {
        return perform(json(post("/login"),
                "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}")).getResponse().getStatus();
    }

    private String update(String name, String email, String mobile, String extra) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"mobile\":\"" + mobile + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private String passwordBody(String current, String next, String confirm) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next
                + "\",\"confirmNewPassword\":\"" + confirm + "\"}";
    }

    private MvcResult patchAccount(String token, String body) throws Exception {
        return perform(bearer(json(patch("/account/me"), body), token));
    }

    private MvcResult changePassword(String token, String body) throws Exception {
        return perform(bearer(json(patch("/account/me/password"), body), token));
    }

    // ---- profile: read ----

    @Test
    void everyRole_readsOwnProfile_withoutSecrets() throws Exception {
        for (UserEntity actor : new UserEntity[]{customer, cashier, admin}) {
            MvcResult result = perform(bearer(get("/account/me"), login(actor.getEmail(), PASSWORD)));

            assertEquals(200, result.getResponse().getStatus());
            JsonNode profile = body(result);
            assertEquals(actor.getName(), profile.get("name").asText());
            assertEquals(actor.getEmail(), profile.get("email").asText());
            assertEquals(actor.getMobile(), profile.get("mobile").asText());
            assertEquals(actor.getRole(), profile.get("role").asText());
            assertTrue(profile.get("enabled").asBoolean());
            assertNotNull(profile.get("createdAt"));
            String raw = result.getResponse().getContentAsString().toLowerCase();
            assertFalse(raw.contains("password"));
            assertFalse(raw.contains("tokenversion"));
            assertFalse(raw.contains("userid"));
            assertFalse(raw.contains(reload(actor).getPassword().toLowerCase()));
        }
    }

    @Test
    void unauthenticatedRequests_areRejected() throws Exception {
        assertEquals(401, perform(get("/account/me")).getResponse().getStatus());
        assertEquals(401, perform(json(patch("/account/me"), update("X", "x@example.com", TestMobiles.next(), null))).getResponse().getStatus());
        assertEquals(401, perform(json(patch("/account/me/password"), passwordBody(PASSWORD, "Newpass123", "Newpass123"))).getResponse().getStatus());
        assertEquals(401, perform(bearer(get("/account/me"), "not.a.jwt")).getResponse().getStatus());
    }

    // ---- profile: update ----

    @Test
    void user_updatesOwnName() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);

        MvcResult result = patchAccount(token, update("  Cara Renamed ", customer.getEmail(), customer.getMobile(), null));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("Cara Renamed", body(result).get("account").get("name").asText());
        assertTrue(body(result).get("token").isNull(), "no new token unless the email changed");
        assertEquals("Cara Renamed", reload(customer).getName());
        // the existing token is still good
        assertEquals(200, perform(bearer(get("/account/me"), token)).getResponse().getStatus());
    }

    @Test
    void mobile_isNormalized_andLoginByTheNewMobileWorks() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);
        String newMobile = TestMobiles.next();
        String typed = "+91 " + newMobile.substring(0, 5) + "-" + newMobile.substring(5);
        String oldMobile = customer.getMobile();

        MvcResult result = patchAccount(token, update(customer.getName(), customer.getEmail(), typed, null));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(newMobile, reload(customer).getMobile());
        assertEquals(newMobile, body(result).get("account").get("mobile").asText());
        assertEquals(200, loginStatus(newMobile, PASSWORD));
        assertEquals(401, loginStatus(oldMobile, PASSWORD));
        assertEquals(200, loginStatus(customer.getEmail(), PASSWORD));
    }

    @Test
    void email_isNormalized_returnsFreshToken_revokesTheOldOne_andLoginByTheNewEmailWorks() throws Exception {
        String oldToken = login(customer.getEmail(), PASSWORD);
        String oldEmail = customer.getEmail();
        String newEmail = "cara-new-" + s + "@example.com";

        MvcResult result = patchAccount(oldToken, update(customer.getName(), "  Cara-New-" + s + "@Example.COM ", customer.getMobile(), null));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(newEmail, reload(customer).getEmail());
        assertEquals(newEmail, body(result).get("account").get("email").asText());
        // the JWT subject was the old email: that token is revoked, the fresh one works
        String freshToken = body(result).get("token").asText();
        assertEquals(401, perform(bearer(get("/account/me"), oldToken)).getResponse().getStatus());
        MvcResult me = perform(bearer(get("/account/me"), freshToken));
        assertEquals(200, me.getResponse().getStatus());
        assertEquals(newEmail, body(me).get("email").asText());
        // login follows the new database state
        assertEquals(200, loginStatus(newEmail, PASSWORD));
        assertEquals(401, loginStatus(oldEmail, PASSWORD));
    }

    @Test
    void duplicateEmailOrMobile_isRejected_andNothingChanges() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);

        MvcResult dupEmail = patchAccount(token, update("Cara", " " + other.getEmail().toUpperCase() + " ", customer.getMobile(), null));
        MvcResult dupMobile = patchAccount(token, update("Cara", customer.getEmail(), "+91 " + other.getMobile(), null));

        assertEquals(409, dupEmail.getResponse().getStatus());
        assertEquals("An account with this email already exists", body(dupEmail).get("message").asText());
        assertEquals(409, dupMobile.getResponse().getStatus());
        assertEquals("An account with this mobile number already exists", body(dupMobile).get("message").asText());
        UserEntity after = reload(customer);
        assertEquals("Cara Customer", after.getName());
        assertEquals(customer.getEmail(), after.getEmail());
        assertEquals(customer.getMobile(), after.getMobile());
        assertEquals(other.getEmail(), reload(other).getEmail());
    }

    @Test
    void keepingYourOwnEmailAndMobile_isNotADuplicate() throws Exception {
        MvcResult result = patchAccount(login(customer.getEmail(), PASSWORD),
                update("Cara Again", customer.getEmail().toUpperCase(), customer.getMobile(), null));

        assertEquals(200, result.getResponse().getStatus());
    }

    @Test
    void invalidProfileValues_areRejected() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);
        String longName = "n".repeat(101);

        assertEquals(400, patchAccount(token, update("", customer.getEmail(), customer.getMobile(), null)).getResponse().getStatus());
        assertEquals(400, patchAccount(token, update(longName, customer.getEmail(), customer.getMobile(), null)).getResponse().getStatus());
        assertEquals(400, patchAccount(token, update("Cara", "not-an-email", customer.getMobile(), null)).getResponse().getStatus());
        assertEquals(400, patchAccount(token, update("Cara", customer.getEmail(), "12345", null)).getResponse().getStatus());
        assertEquals(400, patchAccount(token, update("Cara", customer.getEmail(), "", null)).getResponse().getStatus());
        assertEquals("Cara Customer", reload(customer).getName());
    }

    @Test
    void accountWithoutAMobile_mayLeaveItBlank_orAddOne() throws Exception {
        UserEntity legacy = userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email("legacy-" + s + "@example.com").name("Legacy Admin")
                .role("ROLE_ADMIN").password(passwordEncoder.encode(PASSWORD)).build());
        String token = login(legacy.getEmail(), PASSWORD);

        assertEquals(200, patchAccount(token, update("Legacy Renamed", legacy.getEmail(), "", null)).getResponse().getStatus());
        assertNull(reload(legacy).getMobile());

        String mobile = TestMobiles.next();
        assertEquals(200, patchAccount(token, update("Legacy Renamed", legacy.getEmail(), mobile, null)).getResponse().getStatus());
        assertEquals(mobile, reload(legacy).getMobile());
    }

    @Test
    void cashierAndAdmin_updateOwnProfile_withTheSameEndpoint() throws Exception {
        for (UserEntity actor : new UserEntity[]{cashier, admin}) {
            String token = login(actor.getEmail(), PASSWORD);
            assertEquals(200, patchAccount(token, update(actor.getName() + " II", actor.getEmail(), actor.getMobile(), null)).getResponse().getStatus());
            assertEquals(actor.getName() + " II", reload(actor).getName());
        }
    }

    // ---- ownership / privilege fields ----

    @Test
    void clientSuppliedUserId_cannotTargetAnotherAccount() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);
        String targeting = "\"userId\":\"" + other.getUserId() + "\",\"id\":" + other.getId()
                + ",\"targetUserId\":\"" + other.getUserId() + "\"";

        MvcResult result = patchAccount(token, update("Hijacked", "hijack-" + s + "@example.com", TestMobiles.next(), targeting));

        assertEquals(200, result.getResponse().getStatus());
        // it changed the CALLER, and only the caller
        assertEquals("Hijacked", reload(customer).getName());
        UserEntity untouched = reload(other);
        assertEquals("Otto Other", untouched.getName());
        assertEquals(other.getEmail(), untouched.getEmail());
        assertEquals(other.getMobile(), untouched.getMobile());
        assertEquals(other.getUserId(), untouched.getUserId());

        // and the same for the password endpoint
        String newToken = body(result).get("token").asText();
        assertEquals(204, changePassword(newToken, "{\"userId\":\"" + other.getUserId() + "\",\"currentPassword\":\"" + PASSWORD
                + "\",\"newPassword\":\"Changed456\",\"confirmNewPassword\":\"Changed456\"}").getResponse().getStatus());
        assertTrue(passwordEncoder.matches(PASSWORD, reload(other).getPassword()));
    }

    @Test
    void roleEnabledAndAuthorityFields_areIgnored() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);
        String privileged = "\"role\":\"ROLE_ADMIN\",\"roles\":[\"ROLE_ADMIN\"],\"authorities\":[\"ROLE_ADMIN\"],"
                + "\"permissions\":[\"ALL\"],\"enabled\":false,\"tokenVersion\":99,\"password\":\"Hacked123\"";

        MvcResult result = patchAccount(token, update("Cara", customer.getEmail(), customer.getMobile(), privileged));

        assertEquals(200, result.getResponse().getStatus());
        UserEntity after = reload(customer);
        assertEquals("ROLE_USER", after.getRole());
        assertTrue(after.isAccountEnabled());
        assertEquals(0, after.currentTokenVersion());
        assertTrue(passwordEncoder.matches(PASSWORD, after.getPassword()));
        assertEquals("ROLE_USER", body(result).get("account").get("role").asText());
        // still not an admin
        assertEquals(403, perform(bearer(get("/admin/cashiers"), token)).getResponse().getStatus());
    }

    // ---- account status ----

    @Test
    void disabledAccount_staysBlocked_andCannotReactivateItself() throws Exception {
        String token = login(cashier.getEmail(), PASSWORD);
        perform(json(patch("/admin/cashiers/" + cashier.getUserId() + "/status")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user(admin.getEmail()).roles("ADMIN")), "{\"enabled\":false}"));
        assertFalse(reload(cashier).isAccountEnabled());

        // the old token is refused, so the self-service endpoint is unreachable...
        assertEquals(401, patchAccount(token, update("Casey", cashier.getEmail(), cashier.getMobile(), "\"enabled\":true")).getResponse().getStatus());
        assertEquals(401, perform(bearer(get("/account/me"), token)).getResponse().getStatus());
        // ...login is blocked...
        assertEquals(401, loginStatus(cashier.getEmail(), PASSWORD));
        // ...and nothing flipped the flag
        assertFalse(reload(cashier).isAccountEnabled());
    }

    @Test
    void selfService_cannotDisableTheAccount() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);

        patchAccount(token, update("Cara", customer.getEmail(), customer.getMobile(), "\"enabled\":false,\"active\":false"));

        assertTrue(reload(customer).isAccountEnabled());
        assertEquals(200, perform(bearer(get("/account/me"), token)).getResponse().getStatus());
    }

    // ---- password ----

    @Test
    void passwordChange_withCorrectCurrentPassword_worksAndRevokesTheOldSession() throws Exception {
        String oldToken = login(customer.getEmail(), PASSWORD);
        String oldHash = reload(customer).getPassword();

        MvcResult result = changePassword(oldToken, passwordBody(PASSWORD, "Brandnew789", "Brandnew789"));

        assertEquals(204, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        UserEntity after = reload(customer);
        assertNotEquals(oldHash, after.getPassword());
        assertTrue(after.getPassword().startsWith("$2"), "stored as a BCrypt hash");
        assertNotEquals("Brandnew789", after.getPassword());
        assertTrue(passwordEncoder.matches("Brandnew789", after.getPassword()));
        assertEquals(1, after.currentTokenVersion());

        // old password no longer works, new one does
        assertEquals(401, loginStatus(customer.getEmail(), PASSWORD));
        String newToken = login(customer.getEmail(), "Brandnew789");
        // the pre-change token is revoked; the one from the new login works
        assertEquals(401, perform(bearer(get("/account/me"), oldToken)).getResponse().getStatus());
        assertEquals(200, perform(bearer(get("/account/me"), newToken)).getResponse().getStatus());
    }

    @Test
    void passwordChange_withWrongCurrentPassword_isRejected_andNothingChanges() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);
        String hash = reload(customer).getPassword();

        MvcResult result = changePassword(token, passwordBody("Wrong12345", "Brandnew789", "Brandnew789"));

        assertEquals(400, result.getResponse().getStatus());
        assertEquals("Current password is incorrect", body(result).get("message").asText());
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains("Wrong12345"));
        assertFalse(raw.contains(hash));
        assertEquals(hash, reload(customer).getPassword());
        assertEquals(0, reload(customer).currentTokenVersion());
        // the session is intact
        assertEquals(200, perform(bearer(get("/account/me"), token)).getResponse().getStatus());
    }

    @Test
    void passwordChange_requiresMatchingConfirmation_andThePasswordPolicy() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);
        String hash = reload(customer).getPassword();

        assertEquals(400, changePassword(token, passwordBody(PASSWORD, "Brandnew789", "Different789")).getResponse().getStatus());
        for (String weak : new String[]{"short1", "onlyletters", "12345678"}) {
            assertEquals(400, changePassword(token, passwordBody(PASSWORD, weak, weak)).getResponse().getStatus(), weak);
        }
        assertEquals(400, changePassword(token, passwordBody(PASSWORD, PASSWORD, PASSWORD)).getResponse().getStatus());
        // current password is mandatory, and a new password alone is not enough
        assertEquals(400, changePassword(token, "{\"newPassword\":\"Brandnew789\",\"confirmNewPassword\":\"Brandnew789\"}").getResponse().getStatus());
        assertEquals(400, changePassword(token, passwordBody("", "Brandnew789", "Brandnew789")).getResponse().getStatus());
        assertEquals(hash, reload(customer).getPassword());
    }

    @Test
    void cashierAndAdmin_changeTheirOwnPassword_withTheSameEndpoint() throws Exception {
        for (UserEntity actor : new UserEntity[]{cashier, admin}) {
            String token = login(actor.getEmail(), PASSWORD);
            assertEquals(204, changePassword(token, passwordBody(PASSWORD, "Staffpass123", "Staffpass123")).getResponse().getStatus());
            assertEquals(200, loginStatus(actor.getEmail(), "Staffpass123"));
        }
    }

    @Test
    void noResponse_containsPasswordsHashesOrTokenVersion() throws Exception {
        String token = login(customer.getEmail(), PASSWORD);
        String hash = reload(customer).getPassword();
        String profile = perform(bearer(get("/account/me"), token)).getResponse().getContentAsString();
        String updated = patchAccount(token, update("Cara", customer.getEmail(), customer.getMobile(), null)).getResponse().getContentAsString();
        String failedChange = changePassword(token, passwordBody("Wrong12345", "Brandnew789", "Brandnew789")).getResponse().getContentAsString();

        for (String response : new String[]{profile, updated, failedChange}) {
            assertFalse(response.contains(PASSWORD), response);
            assertFalse(response.contains("Brandnew789"), response);
            assertFalse(response.contains(hash), response);
            assertFalse(response.toLowerCase().contains("tokenversion"), response);
        }
    }

    // ---- historical order snapshot ----

    @Test
    void profileChange_doesNotAlterExistingOrderSnapshots() throws Exception {
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("ac-cat-" + s).name("Account " + s).build());
        ItemEntity item = itemRepository.save(ItemEntity.builder()
                .itemId("ac-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(100).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());
        String token = login(customer.getEmail(), PASSWORD);
        String originalMobile = customer.getMobile();
        MvcResult created = perform(bearer(json(post("/orders"), "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\""
                + item.getItemId() + "\",\"quantity\":1}]}"), token));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        String orderId = body(created).get("orderId").asText();

        MvcResult changed = patchAccount(token, update("Cara Renamed", "cara-moved-" + s + "@example.com", TestMobiles.next(), null));
        assertEquals(200, changed.getResponse().getStatus());

        OrderEntity order = orderEntityRepository.findByOrderId(orderId).orElseThrow();
        assertEquals("Cara Customer", order.getCustomerName());
        assertEquals(originalMobile, order.getPhoneNumber());
        // the order still belongs to the (renamed) account, and it can still open it
        assertEquals(customer.getId(), order.getUser().getId());
        String fresh = body(changed).get("token").asText();
        assertEquals(200, perform(bearer(get("/orders/" + orderId), fresh)).getResponse().getStatus());
        assertEquals("Cara Customer", body(perform(bearer(get("/orders/" + orderId), fresh))).get("customerName").asText());
    }
}
