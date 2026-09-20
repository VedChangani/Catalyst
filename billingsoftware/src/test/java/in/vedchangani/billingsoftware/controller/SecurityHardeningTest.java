package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.AppUserPrincipal;
import in.vedchangani.billingsoftware.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Pre-A6 hardening, end to end on the real security chain, JWT filter, controllers and H2:
 *  1. per-account tokenVersion: password reset and deactivation revoke already-issued JWTs
 *  2. the retired generic user management (/admin/register, /admin/users) is gone
 *  3. unmatched routes answer 404 in the normal error format
 *  4. a cashier may open the detail of a POS order it entered - and nothing else
 * Deliberately NOT @Transactional, so every service transaction really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHardeningTest {

    private static final String PASSWORD = "Secret123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;

    private String s;
    private UserEntity admin;
    private UserEntity cashierA;
    private UserEntity cashierB;
    private UserEntity customerA;
    private UserEntity customerB;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        admin = account("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");
        cashierA = account("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        cashierB = account("Drew Cashier", "drew-" + s + "@example.com", "ROLE_CASHIER");
        customerA = account("Aaron Customer", "aaron-" + s + "@example.com", "ROLE_USER");
        customerB = account("Bella Customer", "bella-" + s + "@example.com", "ROLE_USER");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("sh-cat-" + s).name("Hardening " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("sh-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(1000).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());
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

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserEntity actor) {
        return request.with(user(actor.getEmail()).roles(actor.getRole().replace("ROLE_", "")));
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult loginResult(UserEntity account, String password) throws Exception {
        return perform(json(post("/login"), "{\"identifier\":\"" + account.getEmail() + "\",\"password\":\"" + password + "\"}"));
    }

    private String login(UserEntity account, String password) throws Exception {
        MvcResult result = loginResult(account, password);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("token").asText();
    }

    private int tokenClaimVersion(String token) {
        return jwtUtil.extractTokenVersion(token);
    }

    // A protected cashier-only call, used to probe whether a token still authenticates.
    private int mySalesWith(String token) throws Exception {
        return status(bearer(get("/pos/sales"), token));
    }

    private String posSale(UserEntity cashier, UserEntity linkedCustomer) throws Exception {
        String customerPart = linkedCustomer == null ? "" : "\"customerUserId\":\"" + linkedCustomer.getUserId() + "\",";
        MvcResult result = perform(json(as(post("/pos/orders"), cashier), "{" + customerPart
                + "\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    private String onlineOrder(UserEntity customer) throws Exception {
        MvcResult result = perform(json(as(post("/orders"), customer),
                "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    // =====================================================================================
    // Finding 1: tokenVersion
    // =====================================================================================

    @Test
    void newTokens_carryTheAccountsCurrentTokenVersion() throws Exception {
        assertEquals(0, tokenClaimVersion(login(cashierA, PASSWORD)));

        perform(json(as(post("/admin/cashiers/" + cashierA.getUserId() + "/reset-password"), admin), "{\"password\":\"Fresh4567\"}"));

        assertEquals(1, reload(cashierA).currentTokenVersion());
        assertEquals(1, tokenClaimVersion(login(cashierA, "Fresh4567")));
    }

    @Test
    void passwordReset_revokesTokensIssuedBefore_andTheNewPasswordsTokenWorks() throws Exception {
        String oldToken = login(cashierA, PASSWORD);
        assertEquals(200, mySalesWith(oldToken));

        assertEquals(204, status(json(as(post("/admin/cashiers/" + cashierA.getUserId() + "/reset-password"), admin),
                "{\"password\":\"Fresh4567\",\"tokenVersion\":0}")));

        // the same, still-unexpired token is now refused on every protected API
        assertEquals(401, mySalesWith(oldToken));
        assertEquals(401, status(bearer(get("/pos/customers").param("search", "aa"), oldToken)));
        assertEquals(401, status(bearer(json(post("/pos/orders"), "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\""
                + item.getItemId() + "\",\"quantity\":1}]}"), oldToken)));
        assertEquals(0, orderEntityRepository.count());

        // old password is gone; the new one yields a token that works
        assertEquals(401, loginResult(cashierA, PASSWORD).getResponse().getStatus());
        String newToken = login(cashierA, "Fresh4567");
        assertEquals(200, mySalesWith(newToken));
        // the client-supplied tokenVersion was ignored: the server incremented it exactly once
        assertEquals(1, reload(cashierA).currentTokenVersion());
    }

    @Test
    void deactivation_revokesTokens_andReactivationDoesNotRevive_theOldOnes() throws Exception {
        String beforeDeactivation = login(cashierA, PASSWORD);
        int versionBefore = reload(cashierA).currentTokenVersion();

        assertEquals(200, status(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin), "{\"enabled\":false}")));
        UserEntity disabled = reload(cashierA);
        assertFalse(disabled.isAccountEnabled());
        assertEquals(versionBefore + 1, disabled.currentTokenVersion());
        assertEquals(401, mySalesWith(beforeDeactivation));

        assertEquals(200, status(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin), "{\"enabled\":true}")));
        UserEntity reactivated = reload(cashierA);
        assertTrue(reactivated.isAccountEnabled());
        // reactivation does not touch the version...
        assertEquals(versionBefore + 1, reactivated.currentTokenVersion());
        // ...so the pre-deactivation token stays dead, even though the account is active again
        assertEquals(401, mySalesWith(beforeDeactivation));
        // a fresh login works
        assertEquals(200, mySalesWith(login(cashierA, PASSWORD)));
    }

    @Test
    void tokens_survive_whenNoInvalidationEventHappensToTheirAccount() throws Exception {
        String tokenA = login(cashierA, PASSWORD);
        String customerToken = login(customerA, PASSWORD);

        // events on OTHER accounts, and a reactivation of an already-active account, change nothing
        perform(json(as(post("/admin/cashiers/" + cashierB.getUserId() + "/reset-password"), admin), "{\"password\":\"Fresh4567\"}"));
        perform(json(as(patch("/admin/cashiers/" + cashierB.getUserId() + "/status"), admin), "{\"enabled\":false}"));
        perform(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin), "{\"enabled\":true}"));

        assertEquals(0, reload(cashierA).currentTokenVersion());
        assertEquals(200, mySalesWith(tokenA));
        assertEquals(200, status(bearer(get("/orders/my-orders"), customerToken)));
    }

    @Test
    void aTokenWithAMismatchedVersion_isRejected_evenWithAValidSignature() throws Exception {
        // correctly signed by the server key, right subject, but a version the account never had
        String forged = jwtUtil.generateToken(new AppUserPrincipal(cashierA.getEmail(), "x", true,
                List.of(new SimpleGrantedAuthority("ROLE_CASHIER")), 7));
        assertEquals(7, tokenClaimVersion(forged));

        assertEquals(401, mySalesWith(forged));
    }

    @Test
    void legacyTokenWithoutTheClaim_worksAtVersion0_andIsRejectedOnceTheVersionMoves() throws Exception {
        // a token shaped like those issued before tokenVersion existed (no claim at all)
        String legacy = jwtUtil.generateToken(new User(cashierA.getEmail(), "x",
                List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))));
        String payload = new String(Base64.getUrlDecoder().decode(legacy.split("\\.")[1]), StandardCharsets.UTF_8);
        assertFalse(payload.contains("tokenVersion"));

        assertEquals(200, mySalesWith(legacy));

        perform(json(as(post("/admin/cashiers/" + cashierA.getUserId() + "/reset-password"), admin), "{\"password\":\"Fresh4567\"}"));

        assertEquals(401, mySalesWith(legacy));
    }

    @Test
    void noApiResponse_exposesTokenVersionOrPasswordData() throws Exception {
        String registration = perform(json(post("/register"), "{\"name\":\"Reg\",\"email\":\"reg-" + s
                + "@example.com\",\"mobile\":\"" + TestMobiles.next() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .getResponse().getContentAsString();
        String loginBody = loginResult(cashierA, PASSWORD).getResponse().getContentAsString();
        String cashierCreate = perform(json(as(post("/admin/cashiers"), admin), "{\"name\":\"New\",\"email\":\"new-" + s
                + "@example.com\",\"mobile\":\"" + TestMobiles.next() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .getResponse().getContentAsString();
        String cashierList = perform(as(get("/admin/cashiers"), admin)).getResponse().getContentAsString();
        String statusChange = perform(json(as(patch("/admin/cashiers/" + cashierB.getUserId() + "/status"), admin),
                "{\"enabled\":false}")).getResponse().getContentAsString();

        for (String response : List.of(registration, loginBody, cashierCreate, cashierList, statusChange)) {
            String lower = response.toLowerCase();
            assertFalse(lower.contains("tokenversion"), response);
            assertFalse(lower.contains("password"), response);
            assertFalse(response.contains(reload(cashierA).getPassword()), response);
            assertFalse(response.contains(PASSWORD), response);
        }
    }

    // =====================================================================================
    // Finding 2: retired generic user management
    // =====================================================================================

    @Test
    void retiredUserManagementEndpoints_noLongerExist_evenForAdmin() throws Exception {
        String escalation = "{\"name\":\"Evil\",\"email\":\"evil-" + s + "@example.com\",\"password\":\"secret123\",\"role\":\"ROLE_ADMIN\"}";

        assertEquals(404, status(json(as(post("/admin/register"), admin), escalation)));
        assertEquals(404, status(as(get("/admin/users"), admin)));
        for (UserEntity target : new UserEntity[]{customerA, admin, cashierA}) {
            assertEquals(404, status(as(delete("/admin/users/" + target.getUserId()), admin)));
            assertTrue(userRepository.findByUserId(target.getUserId()).isPresent());
        }
        assertTrue(userRepository.findByEmail("evil-" + s + "@example.com").isEmpty());
    }

    @Test
    void lowerRoles_areRefusedOnTheAdminPaths_andNoAccountIsCreatedOrDeleted() throws Exception {
        String escalation = "{\"name\":\"Evil\",\"email\":\"evil2-" + s + "@example.com\",\"password\":\"secret123\",\"role\":\"ROLE_ADMIN\"}";
        for (UserEntity actor : new UserEntity[]{customerA, cashierA}) {
            assertEquals(403, status(json(as(post("/admin/register"), actor), escalation)));
            assertEquals(403, status(as(get("/admin/users"), actor)));
            assertEquals(403, status(as(delete("/admin/users/" + customerB.getUserId()), actor)));
            assertEquals(403, status(as(get("/admin/cashiers"), actor)));
        }
        assertEquals(401, status(json(post("/admin/register"), escalation)));
        assertTrue(userRepository.findByEmail("evil2-" + s + "@example.com").isEmpty());
        assertTrue(userRepository.findByUserId(customerB.getUserId()).isPresent());
    }

    @Test
    void publicRegistration_stillOnlyCreatesCustomers() throws Exception {
        for (String role : new String[]{"ROLE_ADMIN", "ROLE_CASHIER"}) {
            String email = "pub-" + role.toLowerCase() + "-" + s + "@example.com";
            assertEquals(201, status(json(post("/register"), "{\"name\":\"Pub\",\"email\":\"" + email + "\",\"mobile\":\""
                    + TestMobiles.next() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"" + role + "\"}")));
            assertEquals("ROLE_USER", userRepository.findByEmail(email).orElseThrow().getRole());
        }
    }

    @Test
    void manageCashiers_stillWorks_asTheOnlyStaffAccountPath() throws Exception {
        String email = "staff-" + s + "@example.com";
        assertEquals(201, status(json(as(post("/admin/cashiers"), admin), "{\"name\":\"Staff\",\"email\":\"" + email
                + "\",\"mobile\":\"" + TestMobiles.next() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"ROLE_ADMIN\"}")));
        assertEquals("ROLE_CASHIER", userRepository.findByEmail(email).orElseThrow().getRole());
        assertTrue(perform(as(get("/admin/cashiers"), admin)).getResponse().getContentAsString().contains(email));
    }

    // =====================================================================================
    // Finding 3: unmatched routes -> 404
    // =====================================================================================

    @Test
    void unmatchedRoutes_return404_inTheStandardErrorFormat() throws Exception {
        MvcResult noSuchPath = perform(as(get("/definitely-not-an-endpoint/" + s), admin));
        MvcResult noDeleteForCashiers = perform(as(delete("/admin/cashiers/" + cashierA.getUserId()), admin));

        for (MvcResult result : new MvcResult[]{noSuchPath, noDeleteForCashiers}) {
            assertEquals(404, result.getResponse().getStatus());
            JsonNode error = body(result);
            assertEquals(404, error.get("status").asInt());
            assertEquals("Resource not found", error.get("message").asText());
            assertNotNull(error.get("timestamp"));
            String raw = result.getResponse().getContentAsString();
            assertFalse(raw.contains("Exception"), raw);
            assertFalse(raw.contains("at in.vedchangani"), raw);
        }
        assertTrue(userRepository.findByUserId(cashierA.getUserId()).isPresent());
    }

    @Test
    void otherErrorStatuses_areUnchanged() throws Exception {
        // authentication still precedes routing, authorization and validation still apply
        assertEquals(401, status(get("/definitely-not-an-endpoint")));
        assertEquals(403, status(as(get("/admin/cashiers"), customerA)));
        assertEquals(400, status(json(as(post("/admin/cashiers"), admin), "{}")));
        assertEquals(404, status(json(as(patch("/admin/cashiers/unknown-id/status"), admin), "{\"enabled\":false}")));
    }

    // =====================================================================================
    // Finding 4: cashier order detail
    // =====================================================================================

    @Test
    void cashier_opensOwnPosSales_walkInAndRegisteredCustomer() throws Exception {
        String walkIn = posSale(cashierA, null);
        String registered = posSale(cashierA, customerA);

        for (String orderId : new String[]{walkIn, registered}) {
            MvcResult result = perform(as(get("/orders/" + orderId), cashierA));
            assertEquals(200, result.getResponse().getStatus());
            JsonNode order = body(result);
            assertEquals(orderId, order.get("orderId").asText());
            assertEquals("POS", order.get("salesChannel").asText());
            assertEquals(cashierA.getUserId(), order.get("createdBy").get("userId").asText());
            assertEquals(1, order.get("items").size());
            String raw = result.getResponse().getContentAsString().toLowerCase();
            assertFalse(raw.contains("signature"));
            assertFalse(raw.contains("password"));
        }
        assertEquals("Aaron Customer", body(perform(as(get("/orders/" + registered), cashierA))).get("customerName").asText());
    }

    @Test
    void cashier_cannotOpenAnotherCashiersSale_orAnyOnlineOrder() throws Exception {
        String othersSale = posSale(cashierB, customerA);
        String onlineOrder = onlineOrder(customerA);

        assertEquals(403, status(as(get("/orders/" + othersSale), cashierA)));
        assertEquals(403, status(as(get("/orders/" + onlineOrder), cashierA)));
        // a client-supplied cashier id changes nothing
        assertEquals(403, status(as(get("/orders/" + othersSale).param("cashierId", cashierB.getUserId()), cashierA)));
        assertEquals(404, status(as(get("/orders/ORD-does-not-exist"), cashierA)));
    }

    @Test
    void customerDetailRules_areUnchanged() throws Exception {
        String ownOnline = onlineOrder(customerA);
        String othersOnline = onlineOrder(customerB);
        String walkIn = posSale(cashierA, null);
        String linkedPos = posSale(cashierA, customerA);

        assertEquals(200, status(as(get("/orders/" + ownOnline), customerA)));
        assertEquals(200, status(as(get("/orders/" + linkedPos), customerA)));
        assertEquals(403, status(as(get("/orders/" + othersOnline), customerA)));
        // knowing a walk-in sale's id grants a customer nothing
        assertEquals(403, status(as(get("/orders/" + walkIn), customerA)));
        assertEquals(403, status(as(get("/orders/" + walkIn), customerB)));
    }

    @Test
    void adminOrderAccess_isUnchanged() throws Exception {
        String posOrder = posSale(cashierA, null);
        String online = onlineOrder(customerA);

        String all = perform(as(get("/admin/orders"), admin)).getResponse().getContentAsString();
        assertTrue(all.contains(posOrder));
        assertTrue(all.contains(online));
        assertEquals(200, status(as(get("/orders/latest"), admin)));
        // the per-order customer/cashier detail endpoint was never an admin endpoint
        assertEquals(403, status(as(get("/orders/" + posOrder), admin)));
    }
}
