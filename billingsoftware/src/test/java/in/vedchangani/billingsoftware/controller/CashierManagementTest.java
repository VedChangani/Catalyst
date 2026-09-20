package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.SalesChannel;
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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A5: ADMIN cashier management (/admin/cashiers), deactivation semantics, and cashier My Sales
 * (/pos/sales), through the real SecurityConfig, JWT filter, controllers, services and H2 database.
 * Deliberately NOT @Transactional so every service transaction really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CashierManagementTest {

    private static final String PASSWORD = "Secret123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String s;
    private UserEntity admin;
    private UserEntity cashierA;
    private UserEntity cashierB;
    private UserEntity customer;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        admin = account("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");
        cashierA = account("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        cashierB = account("Drew Cashier", "drew-" + s + "@example.com", "ROLE_CASHIER");
        customer = account("Cara Customer", "cara-" + s + "@example.com", "ROLE_USER");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("cm-cat-" + s).name("Cashier mgmt " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("cm-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
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

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserEntity actor) {
        return request.with(user(actor.getEmail()).roles(actor.getRole().replace("ROLE_", "")));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createBody(String name, String email, String mobile, String extra) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"mobile\":\"" + mobile
                + "\",\"password\":\"" + PASSWORD + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private String loginToken(String identifier, String password) throws Exception {
        MvcResult result = perform(json(post("/login"),
                "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("token").asText();
    }

    private String posSale(UserEntity cashier, UserEntity linkedCustomer, String method, int quantity) throws Exception {
        String customerPart = linkedCustomer == null ? "" : "\"customerUserId\":\"" + linkedCustomer.getUserId() + "\",";
        MvcResult result = perform(json(as(post("/pos/orders"), cashier), "{" + customerPart + "\"paymentMethod\":\""
                + method + "\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":" + quantity + "}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    private String onlineOrder(int quantity) throws Exception {
        MvcResult result = perform(json(as(post("/orders"), customer), "{\"paymentMethod\":\"CASH\",\"cartItems\":"
                + "[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":" + quantity + "}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    private OrderEntity stored(String orderId) {
        return orderEntityRepository.findByOrderId(orderId).orElseThrow();
    }

    private JsonNode rowFor(JsonNode list, UserEntity cashier) {
        for (JsonNode row : list) {
            if (cashier.getUserId().equals(row.get("userId").asText())) {
                return row;
            }
        }
        return null;
    }

    // ---- creation ----

    @Test
    void admin_createsEnabledCashier_withHashedPassword_andNoSecretsInResponse() throws Exception {
        String email = "new-cashier-" + s + "@example.com";
        String mobile = TestMobiles.next();

        MvcResult result = perform(json(as(post("/admin/cashiers"), admin), createBody("New Cashier", email, mobile, null)));

        assertEquals(201, result.getResponse().getStatus());
        JsonNode created = body(result);
        assertEquals(email, created.get("email").asText());
        assertEquals(mobile, created.get("mobile").asText());
        assertTrue(created.get("enabled").asBoolean());
        assertEquals(0, created.get("ordersProcessed").asLong());
        assertFalse(created.has("password"));
        assertFalse(created.has("role"));

        UserEntity saved = userRepository.findByEmail(email).orElseThrow();
        assertEquals("ROLE_CASHIER", saved.getRole());
        assertTrue(saved.isAccountEnabled());
        assertTrue(saved.getPassword().startsWith("$2"));
        assertTrue(passwordEncoder.matches(PASSWORD, saved.getPassword()));
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains(PASSWORD));
        assertFalse(raw.contains(saved.getPassword()));

        // and the new cashier can sign in and use the POS
        loginToken(email, PASSWORD);
    }

    @Test
    void clientCannotChooseRole_orDisabledStatus_forANewCashier() throws Exception {
        String asAdmin = "admin-try-" + s + "@example.com";
        String asUser = "user-try-" + s + "@example.com";
        assertEquals(201, perform(json(as(post("/admin/cashiers"), admin), createBody("X", asAdmin, TestMobiles.next(),
                "\"role\":\"ROLE_ADMIN\",\"roles\":[\"ROLE_ADMIN\"],\"authorities\":[\"ROLE_ADMIN\"],\"enabled\":false,\"userId\":\"forced\""))).getResponse().getStatus());
        assertEquals(201, perform(json(as(post("/admin/cashiers"), admin), createBody("Y", asUser, TestMobiles.next(),
                "\"role\":\"ROLE_USER\""))).getResponse().getStatus());

        UserEntity a = userRepository.findByEmail(asAdmin).orElseThrow();
        assertEquals("ROLE_CASHIER", a.getRole());
        assertTrue(a.isAccountEnabled());
        assertNotEquals("forced", a.getUserId());
        assertEquals("ROLE_CASHIER", userRepository.findByEmail(asUser).orElseThrow().getRole());
    }

    @Test
    void duplicateEmailOrMobile_isRejected_withoutTouchingTheExistingAccount() throws Exception {
        MvcResult dupEmail = perform(json(as(post("/admin/cashiers"), admin),
                createBody("Dup", " " + customer.getEmail().toUpperCase() + " ", TestMobiles.next(), null)));
        MvcResult dupMobile = perform(json(as(post("/admin/cashiers"), admin),
                createBody("Dup", "dup-" + s + "@example.com", "+91 " + customer.getMobile(), null)));

        assertEquals(409, dupEmail.getResponse().getStatus());
        assertEquals("An account with this email already exists", body(dupEmail).get("message").asText());
        assertEquals(409, dupMobile.getResponse().getStatus());
        assertEquals("An account with this mobile number already exists", body(dupMobile).get("message").asText());
        assertEquals("ROLE_USER", userRepository.findByUserId(customer.getUserId()).orElseThrow().getRole());
        assertTrue(userRepository.findByEmail("dup-" + s + "@example.com").isEmpty());
    }

    @Test
    void invalidCreateRequests_areRejected() throws Exception {
        assertEquals(400, perform(json(as(post("/admin/cashiers"), admin),
                createBody("Bad", "not-an-email", TestMobiles.next(), null))).getResponse().getStatus());
        assertEquals(400, perform(json(as(post("/admin/cashiers"), admin),
                createBody("Bad", "bad-" + s + "@example.com", "12345", null))).getResponse().getStatus());
        assertEquals(400, perform(json(as(post("/admin/cashiers"), admin),
                "{\"name\":\"Bad\",\"email\":\"bad2-" + s + "@example.com\",\"mobile\":\"" + TestMobiles.next()
                        + "\",\"password\":\"short\"}")).getResponse().getStatus());
        assertEquals(400, perform(json(as(post("/admin/cashiers"), admin), "{}")).getResponse().getStatus());
    }

    @Test
    void publicRegistration_stillCannotCreateCashierOrAdmin() throws Exception {
        for (String role : new String[]{"ROLE_CASHIER", "ROLE_ADMIN"}) {
            String email = "public-" + role.toLowerCase() + "-" + s + "@example.com";
            MvcResult result = perform(json(post("/register"), createBody("Pub", email, TestMobiles.next(),
                    "\"role\":\"" + role + "\",\"enabled\":true")));
            assertEquals(201, result.getResponse().getStatus());
            assertEquals("ROLE_USER", userRepository.findByEmail(email).orElseThrow().getRole());
        }
    }

    // ---- authorization of every management endpoint ----

    @Test
    void everyCashierManagementEndpoint_isAdminOnly() throws Exception {
        String create = createBody("Nope", "nope-" + s + "@example.com", TestMobiles.next(), null);
        for (UserEntity actor : new UserEntity[]{customer, cashierA}) {
            assertEquals(403, perform(json(as(post("/admin/cashiers"), actor), create)).getResponse().getStatus());
            assertEquals(403, perform(as(get("/admin/cashiers"), actor)).getResponse().getStatus());
            assertEquals(403, perform(json(as(patch("/admin/cashiers/" + cashierB.getUserId() + "/status"), actor),
                    "{\"enabled\":false}")).getResponse().getStatus());
            assertEquals(403, perform(json(as(post("/admin/cashiers/" + cashierB.getUserId() + "/reset-password"), actor),
                    "{\"password\":\"Hijack123\"}")).getResponse().getStatus());
        }
        assertEquals(401, perform(get("/admin/cashiers")).getResponse().getStatus());
        assertEquals(401, perform(json(post("/admin/cashiers"), create)).getResponse().getStatus());

        assertTrue(userRepository.findByEmail("nope-" + s + "@example.com").isEmpty());
        UserEntity b = userRepository.findByUserId(cashierB.getUserId()).orElseThrow();
        assertTrue(b.isAccountEnabled());
        assertTrue(passwordEncoder.matches(PASSWORD, b.getPassword()));
        // there is no delete endpoint for cashiers
        int deleteStatus = perform(as(delete("/admin/cashiers/" + cashierB.getUserId()), admin)).getResponse().getStatus();
        assertTrue(deleteStatus >= 400, "DELETE must not succeed: " + deleteStatus);
        assertTrue(userRepository.findByUserId(cashierB.getUserId()).isPresent());
    }

    // ---- listing & metrics ----

    @Test
    void list_containsOnlyCashiers_withoutSecrets() throws Exception {
        MvcResult result = perform(as(get("/admin/cashiers"), admin));

        assertEquals(200, result.getResponse().getStatus());
        JsonNode list = body(result);
        assertNotNull(rowFor(list, cashierA));
        assertNotNull(rowFor(list, cashierB));
        assertNull(rowFor(list, customer));
        assertNull(rowFor(list, admin));
        for (JsonNode row : list) {
            assertEquals("ROLE_CASHIER", userRepository.findByUserId(row.get("userId").asText()).orElseThrow().getRole());
            assertFalse(row.has("password"));
            assertFalse(row.has("role"));
        }
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains(cashierA.getPassword()));
        assertFalse(raw.toLowerCase().contains("password"));
    }

    @Test
    void metrics_countOnlyPosOrdersCreatedByEachCashier_andRevenueOnlyFromPaid() throws Exception {
        // cashier A: paid walk-in (10.10), paid registered-customer sale x2 (20.20),
        //            pending UPI (10.10), cancelled (10.10)
        String paidWalkIn = posSale(cashierA, null, "CASH", 1);
        String paidLinked = posSale(cashierA, customer, "CASH", 2);
        String pendingUpi = posSale(cashierA, null, "UPI", 1);
        String cancelled = posSale(cashierA, null, "CASH", 1);
        OrderEntity c = stored(cancelled);
        c.setOrderStatus(OrderStatus.CANCELLED);
        orderEntityRepository.save(c);
        // cashier B: one paid sale x3 (30.30)
        String bSale = posSale(cashierB, customer, "CASH", 3);
        // ONLINE order by the customer: must count for nobody
        onlineOrder(5);

        // deterministic "last sale" times
        LocalDateTime latestA = LocalDateTime.of(2026, 3, 1, 12, 0);
        for (String id : new String[]{paidWalkIn, paidLinked, pendingUpi, cancelled}) {
            OrderEntity o = stored(id);
            o.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
            orderEntityRepository.save(o);
        }
        OrderEntity newest = stored(paidLinked);
        newest.setCreatedAt(latestA);
        orderEntityRepository.save(newest);
        OrderEntity b = stored(bSale);
        b.setCreatedAt(LocalDateTime.of(2026, 2, 2, 8, 30));
        orderEntityRepository.save(b);

        JsonNode list = body(perform(as(get("/admin/cashiers"), admin)));
        JsonNode rowA = rowFor(list, cashierA);
        JsonNode rowB = rowFor(list, cashierB);

        assertEquals(4, rowA.get("ordersProcessed").asLong());
        assertEquals(30.30, rowA.get("posRevenue").asDouble(), 0.0001);
        assertEquals(latestA, LocalDateTime.parse(rowA.get("lastPosSaleAt").asText()));

        assertEquals(1, rowB.get("ordersProcessed").asLong());
        assertEquals(30.30, rowB.get("posRevenue").asDouble(), 0.0001);
        assertEquals(LocalDateTime.of(2026, 2, 2, 8, 30), LocalDateTime.parse(rowB.get("lastPosSaleAt").asText()));

        // a cashier without POS sales
        UserEntity idle = account("Idle Cashier", "idle-" + s + "@example.com", "ROLE_CASHIER");
        JsonNode idleRow = rowFor(body(perform(as(get("/admin/cashiers"), admin))), idle);
        assertEquals(0, idleRow.get("ordersProcessed").asLong());
        assertEquals(0.0, idleRow.get("posRevenue").asDouble(), 0.0001);
        assertTrue(idleRow.get("lastPosSaleAt").isNull());
    }

    // ---- status ----

    @Test
    void deactivate_keepsHistory_blocksLoginAndExistingTokens_andReactivateRestoresAccess() throws Exception {
        String token = loginToken(cashierA.getEmail(), PASSWORD);
        String orderX = posSale(cashierA, customer, "CASH", 1);

        MvcResult off = perform(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin),
                "{\"enabled\":false,\"role\":\"ROLE_ADMIN\"}"));
        assertEquals(200, off.getResponse().getStatus());
        assertFalse(body(off).get("enabled").asBoolean());
        assertEquals(1, body(off).get("ordersProcessed").asLong());
        // repeating the same status is a harmless no-op
        assertEquals(200, perform(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin),
                "{\"enabled\":false}")).getResponse().getStatus());

        UserEntity after = userRepository.findByUserId(cashierA.getUserId()).orElseThrow();
        assertFalse(after.isAccountEnabled());
        assertEquals("ROLE_CASHIER", after.getRole());

        // historical order X is untouched and still visible to the admin
        OrderEntity x = stored(orderX);
        assertEquals(cashierA.getId(), x.getCreatedBy().getId());
        assertEquals(customer.getId(), x.getUser().getId());
        assertEquals(OrderStatus.PAID, x.getOrderStatus());
        assertTrue(perform(as(get("/admin/orders"), admin)).getResponse().getContentAsString().contains(orderX));
        assertTrue(perform(as(get("/admin/orders").param("createdByUserId", cashierA.getUserId()), admin))
                .getResponse().getContentAsString().contains(orderX));

        // the token issued BEFORE deactivation no longer works on any protected API
        String posBody = "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}";
        assertEquals(401, perform(json(post("/pos/orders").header("Authorization", "Bearer " + token), posBody))
                .getResponse().getStatus());
        assertEquals(401, perform(get("/pos/sales").header("Authorization", "Bearer " + token)).getResponse().getStatus());
        assertEquals(401, perform(get("/pos/customers").param("search", "ca").header("Authorization", "Bearer " + token))
                .getResponse().getStatus());
        assertEquals(1, orderEntityRepository.findByCreatedBy_IdAndSalesChannelOrderByCreatedAtDesc(cashierA.getId(), SalesChannel.POS).size());

        // login is refused, and even the right password gets exactly the generic error (A8): the
        // response never reveals that the account exists or is deactivated
        MvcResult rightPassword = perform(json(post("/login"),
                "{\"identifier\":\"" + cashierA.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"));
        assertEquals(401, rightPassword.getResponse().getStatus());
        assertEquals("Email/mobile or password is incorrect", body(rightPassword).get("message").asText());
        assertFalse(body(rightPassword).has("token"));
        MvcResult wrongPassword = perform(json(post("/login"),
                "{\"identifier\":\"" + cashierA.getEmail() + "\",\"password\":\"Wrong1234\"}"));
        assertEquals(401, wrongPassword.getResponse().getStatus());
        assertEquals("Email/mobile or password is incorrect", body(wrongPassword).get("message").asText());

        // reactivate: the cashier can sign in and sell again, with the same history
        MvcResult on = perform(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin),
                "{\"enabled\":true}"));
        assertEquals(200, on.getResponse().getStatus());
        assertTrue(body(on).get("enabled").asBoolean());
        String newToken = loginToken(cashierA.getMobile(), PASSWORD);
        assertEquals(201, perform(json(post("/pos/orders").header("Authorization", "Bearer " + newToken), posBody))
                .getResponse().getStatus());
        assertEquals(cashierA.getId(), stored(orderX).getCreatedBy().getId());
    }

    @Test
    void statusAndReset_onlyTargetCashiers() throws Exception {
        for (UserEntity nonCashier : new UserEntity[]{customer, admin}) {
            assertEquals(404, perform(json(as(patch("/admin/cashiers/" + nonCashier.getUserId() + "/status"), admin),
                    "{\"enabled\":false}")).getResponse().getStatus());
            assertEquals(404, perform(json(as(post("/admin/cashiers/" + nonCashier.getUserId() + "/reset-password"), admin),
                    "{\"password\":\"NewPass123\"}")).getResponse().getStatus());
            UserEntity untouched = userRepository.findByUserId(nonCashier.getUserId()).orElseThrow();
            assertTrue(untouched.isAccountEnabled());
            assertTrue(passwordEncoder.matches(PASSWORD, untouched.getPassword()));
        }
        assertEquals(404, perform(json(as(patch("/admin/cashiers/does-not-exist/status"), admin),
                "{\"enabled\":false}")).getResponse().getStatus());
        assertEquals(400, perform(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin),
                "{}")).getResponse().getStatus());
    }

    @Test
    void cashierCannotBeDeletedThroughTheRetiredUserEndpoint() throws Exception {
        String orderX = posSale(cashierA, null, "CASH", 1);

        // the legacy DELETE /admin/users/{id} no longer exists at all
        assertEquals(404, perform(as(delete("/admin/users/" + cashierA.getUserId()), admin)).getResponse().getStatus());

        assertTrue(userRepository.findByUserId(cashierA.getUserId()).isPresent());
        assertEquals(cashierA.getId(), stored(orderX).getCreatedBy().getId());
    }

    // ---- password reset ----

    @Test
    void admin_resetsCashierPassword_hashed_notReturned_andOnlyTheNewPasswordWorks() throws Exception {
        MvcResult result = perform(json(as(post("/admin/cashiers/" + cashierA.getUserId() + "/reset-password"), admin),
                "{\"password\":\"Fresh4567\",\"role\":\"ROLE_ADMIN\"}"));

        assertEquals(204, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        UserEntity after = userRepository.findByUserId(cashierA.getUserId()).orElseThrow();
        assertTrue(after.getPassword().startsWith("$2"));
        assertTrue(passwordEncoder.matches("Fresh4567", after.getPassword()));
        assertEquals("ROLE_CASHIER", after.getRole());
        assertTrue(after.isAccountEnabled());

        assertEquals(401, perform(json(post("/login"), "{\"identifier\":\"" + cashierA.getEmail()
                + "\",\"password\":\"" + PASSWORD + "\"}")).getResponse().getStatus());
        loginToken(cashierA.getEmail(), "Fresh4567");
        // other accounts are unaffected
        loginToken(cashierB.getEmail(), PASSWORD);
    }

    @Test
    void passwordReset_enforcesThePasswordPolicy() throws Exception {
        for (String weak : new String[]{"short1", "onlyletters", "12345678"}) {
            assertEquals(400, perform(json(as(post("/admin/cashiers/" + cashierA.getUserId() + "/reset-password"), admin),
                    "{\"password\":\"" + weak + "\"}")).getResponse().getStatus(), weak);
        }
        assertTrue(passwordEncoder.matches(PASSWORD,
                userRepository.findByUserId(cashierA.getUserId()).orElseThrow().getPassword()));
    }

    // ---- My Sales ----

    @Test
    void mySales_isCreatedByTheAuthenticatedCashier_only() throws Exception {
        String walkIn = posSale(cashierA, null, "CASH", 1);
        String registered = posSale(cashierA, customer, "UPI", 1);
        String otherCashiers = posSale(cashierB, customer, "CASH", 1);
        String online = onlineOrder(1);
        // a historical admin-entered POS sale is not the cashier's
        String adminHistorical = posSale(cashierB, null, "CASH", 1);
        OrderEntity h = stored(adminHistorical);
        h.setCreatedBy(admin);
        orderEntityRepository.save(h);

        String token = loginToken(cashierA.getEmail(), PASSWORD);
        // a client-supplied cashier id is simply ignored
        MvcResult result = perform(get("/pos/sales").param("cashierId", cashierB.getUserId())
                .param("createdByUserId", cashierB.getUserId()).header("Authorization", "Bearer " + token));

        assertEquals(200, result.getResponse().getStatus());
        JsonNode sales = body(result);
        assertEquals(2, sales.size());
        String raw = result.getResponse().getContentAsString();
        assertTrue(raw.contains(walkIn));
        assertTrue(raw.contains(registered));
        assertFalse(raw.contains(otherCashiers));
        assertFalse(raw.contains(online));
        assertFalse(raw.contains(adminHistorical));
        for (JsonNode sale : sales) {
            assertEquals(SalesChannel.POS.name(), sale.get("salesChannel").asText());
        }
    }
}
