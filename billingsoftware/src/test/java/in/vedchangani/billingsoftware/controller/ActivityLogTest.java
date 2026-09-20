package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.AuditLogEntity;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.repository.AuditLogRepository;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A7: the persistent activity/audit log - what gets recorded (and what does not), who can read
 * what, and that records are write-once - through the real security chain, JWT filter, controllers,
 * services and the H2 database. Deliberately NOT @Transactional: every business transaction really
 * commits or rolls back, which is exactly what the audit semantics depend on.
 *
 * The audit table is shared with every other test class in the same Spring context, so every
 * assertion is scoped to this test's own freshly created actors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityLogTest {

    private static final String PASSWORD = "Secret123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AuditService auditService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    private String s;
    private UserEntity admin;
    private UserEntity cashierA;
    private UserEntity cashierB;
    private UserEntity customerA;
    private UserEntity customerB;
    private CategoryEntity category;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        admin = account("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");
        cashierA = account("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        cashierB = account("Drew Cashier", "drew-" + s + "@example.com", "ROLE_CASHIER");
        customerA = account("Aaron Customer", "aaron-" + s + "@example.com", "ROLE_USER");
        customerB = account("Bella Customer", "bella-" + s + "@example.com", "ROLE_USER");
        category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("al-cat-" + s).name("Activity " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("al-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(100).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());
    }

    @AfterEach
    void tearDown() {
        // audit rows are write-once by design and have no FK to users, so they are left in place
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

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request).getResponse().getStatus();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String login(UserEntity account, String password) throws Exception {
        MvcResult result = perform(json(post("/login"),
                "{\"identifier\":\"" + account.getEmail() + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("token").asText();
    }

    // Every event of one actor, newest first, read through the ADMIN system-activity API.
    private List<JsonNode> eventsOf(UserEntity actor) throws Exception {
        return systemActivity("actorUserId=" + actor.getUserId() + "&size=100");
    }

    private List<JsonNode> systemActivity(String query) throws Exception {
        MvcResult result = perform(as(get("/admin/activity?" + query), admin));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        List<JsonNode> events = new ArrayList<>();
        body(result).get("content").forEach(events::add);
        return events;
    }

    private List<JsonNode> ofAction(List<JsonNode> events, AuditAction action) {
        return events.stream().filter(e -> action.name().equals(e.get("action").asText())).toList();
    }

    private JsonNode single(UserEntity actor, AuditAction action) throws Exception {
        List<JsonNode> matching = ofAction(eventsOf(actor), action);
        assertEquals(1, matching.size(), action + " events for " + actor.getName() + ": " + matching);
        return matching.get(0);
    }

    private void assertActor(JsonNode event, UserEntity actor) {
        assertEquals(actor.getUserId(), event.get("actorUserId").asText());
        assertEquals(actor.getRole(), event.get("actorRole").asText());
        assertEquals(actor.getName(), event.get("actorName").asText());
    }

    private String onlineOrder(UserEntity customer, String method, int quantity) throws Exception {
        MvcResult result = perform(json(as(post("/orders"), customer), "{\"paymentMethod\":\"" + method
                + "\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":" + quantity + "}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    private String posSale(UserEntity cashier, UserEntity linkedCustomer, String method) throws Exception {
        String customerPart = linkedCustomer == null ? "" : "\"customerUserId\":\"" + linkedCustomer.getUserId() + "\",";
        MvcResult result = perform(json(as(post("/pos/orders"), cashier), "{" + customerPart + "\"paymentMethod\":\""
                + method + "\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    private String signature(String razorpayOrderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(razorpayKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((razorpayOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }

    private void tieToRazorpay(String orderId, String razorpayOrderId) {
        OrderEntity order = orderEntityRepository.findByOrderId(orderId).orElseThrow();
        order.getPaymentDetails().setRazorpayOrderId(razorpayOrderId);
        orderEntityRepository.save(order);
    }

    private String verifyBody(String orderId, String razorpayOrderId, String paymentId, String signature) {
        return "{\"orderId\":\"" + orderId + "\",\"razorpayOrderId\":\"" + razorpayOrderId
                + "\",\"razorpayPaymentId\":\"" + paymentId + "\",\"razorpaySignature\":\"" + signature + "\"}";
    }

    // =====================================================================================
    // Visibility
    // =====================================================================================

    @Test
    void customerAndCashier_readOnlyTheirOwnActivity_throughTheRealJwtFlow() throws Exception {
        String customerToken = login(customerA, PASSWORD);   // AUTH_LOGIN_SUCCESS (customer A)
        login(customerB, PASSWORD);                          // AUTH_LOGIN_SUCCESS (customer B)
        String cashierToken = login(cashierA, PASSWORD);     // AUTH_LOGIN_SUCCESS (cashier A)
        login(cashierB, PASSWORD);
        onlineOrder(customerA, "CASH", 1);
        onlineOrder(customerB, "CASH", 1);
        posSale(cashierA, customerA, "CASH");               // actor = cashier A, even though user = customer A
        posSale(cashierB, null, "CASH");

        JsonNode mine = body(perform(bearer(get("/activity/me"), customerToken)));
        List<String> actions = new ArrayList<>();
        for (JsonNode event : mine.get("content")) {
            assertEquals(customerA.getUserId(), event.get("actorUserId").asText());
            actions.add(event.get("action").asText());
        }
        assertEquals(List.of("ONLINE_ORDER_CREATED", "AUTH_LOGIN_SUCCESS"), actions);

        JsonNode cashiers = body(perform(bearer(get("/activity/me"), cashierToken)));
        List<String> cashierActions = new ArrayList<>();
        for (JsonNode event : cashiers.get("content")) {
            assertEquals(cashierA.getUserId(), event.get("actorUserId").asText());
            cashierActions.add(event.get("action").asText());
        }
        assertEquals(List.of("POS_ORDER_CREATED", "AUTH_LOGIN_SUCCESS"), cashierActions);
    }

    @Test
    void ownershipCannotBeChangedWithQueryParameters() throws Exception {
        login(customerB, PASSWORD);
        onlineOrder(customerB, "CASH", 1);
        String token = login(customerA, PASSWORD);
        String cashierToken = login(cashierA, PASSWORD);
        posSale(cashierB, null, "CASH");

        for (String params : new String[]{"userId=" + customerB.getUserId(), "actorUserId=" + customerB.getUserId(),
                "actorRole=ROLE_ADMIN", "actorId=" + customerB.getId()}) {
            JsonNode page = body(perform(bearer(get("/activity/me?" + params), token)));
            assertEquals(1, page.get("totalElements").asLong(), params);
            assertEquals(customerA.getUserId(), page.get("content").get(0).get("actorUserId").asText(), params);
        }
        JsonNode cashierPage = body(perform(bearer(get("/activity/me?actorUserId=" + cashierB.getUserId()), cashierToken)));
        assertEquals(1, cashierPage.get("totalElements").asLong());
        assertEquals(cashierA.getUserId(), cashierPage.get("content").get(0).get("actorUserId").asText());
    }

    @Test
    void systemActivity_isAdminOnly() throws Exception {
        assertEquals(401, status(get("/activity/me")));
        assertEquals(401, status(get("/admin/activity")));
        assertEquals(403, status(as(get("/admin/activity"), customerA)));
        assertEquals(403, status(as(get("/admin/activity"), cashierA)));
        assertEquals(403, status(as(get("/admin/activity?actorUserId=" + customerB.getUserId()), customerA)));
        assertEquals(200, status(as(get("/admin/activity"), admin)));
        // an admin can also read its own personal log
        assertEquals(200, status(as(get("/activity/me"), admin)));
    }

    @Test
    void admin_seesEveryonesEvents_andCanFilterThem() throws Exception {
        login(customerA, PASSWORD);
        String orderId = onlineOrder(customerA, "CASH", 1);
        posSale(cashierA, null, "CASH");
        perform(json(as(patch("/admin/items/" + item.getItemId() + "/stock"), admin), "{\"delta\":5}"));

        // system-wide: all three actors are visible to the admin
        assertFalse(eventsOf(customerA).isEmpty());
        assertFalse(eventsOf(cashierA).isEmpty());
        assertFalse(eventsOf(admin).isEmpty());

        List<JsonNode> byAction = systemActivity("action=ONLINE_ORDER_CREATED&actorUserId=" + customerA.getUserId());
        assertEquals(1, byAction.size());
        assertEquals(orderId, byAction.get(0).get("targetId").asText());

        List<JsonNode> byRole = systemActivity("actorRole=ROLE_CASHIER&action=POS_ORDER_CREATED&size=100");
        assertTrue(byRole.stream().allMatch(e -> "ROLE_CASHIER".equals(e.get("actorRole").asText())));
        assertTrue(byRole.stream().anyMatch(e -> cashierA.getUserId().equals(e.get("actorUserId").asText())));

        List<JsonNode> byTarget = systemActivity("targetType=ITEM&actorUserId=" + admin.getUserId());
        assertEquals(1, byTarget.size());
        assertEquals("INVENTORY_ADJUSTED", byTarget.get(0).get("action").asText());

        String today = LocalDate.now().toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertEquals(2, systemActivity("dateFrom=" + today + "&dateTo=" + today + "&actorUserId=" + customerA.getUserId()).size());
        assertEquals(0, systemActivity("dateFrom=" + tomorrow + "&actorUserId=" + customerA.getUserId()).size());

        // an unknown actor id matches nothing (it must not fall back to "every actor")
        assertEquals(0, systemActivity("actorUserId=no-such-user").size());
        // invalid filters are rejected, not ignored
        assertEquals(400, status(as(get("/admin/activity?actorRole=ROLE_ROOT"), admin)));
        assertEquals(400, status(as(get("/admin/activity?action=NOT_AN_ACTION"), admin)));
        assertEquals(400, status(as(get("/admin/activity?dateFrom=" + tomorrow + "&dateTo=" + today), admin)));
    }

    @Test
    void pagination_isServerSide_newestFirst_andBounded() throws Exception {
        String token = login(customerA, PASSWORD);
        for (int i = 0; i < 4; i++) {
            onlineOrder(customerA, "CASH", 1);
        }

        JsonNode first = body(perform(bearer(get("/activity/me?page=0&size=2"), token)));
        JsonNode second = body(perform(bearer(get("/activity/me?page=1&size=2"), token)));
        JsonNode last = body(perform(bearer(get("/activity/me?page=2&size=2"), token)));

        assertEquals(5, first.get("totalElements").asLong());
        assertEquals(3, first.get("totalPages").asInt());
        assertEquals(2, first.get("content").size());
        assertTrue(first.get("first").asBoolean());
        assertTrue(last.get("last").asBoolean());
        assertEquals("AUTH_LOGIN_SUCCESS", last.get("content").get(0).get("action").asText());

        // strictly newest first across the pages
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode page : new JsonNode[]{first, second, last}) page.get("content").forEach(all::add);
        for (int i = 1; i < all.size(); i++) {
            LocalDateTime newer = LocalDateTime.parse(all.get(i - 1).get("createdAt").asText());
            LocalDateTime older = LocalDateTime.parse(all.get(i).get("createdAt").asText());
            assertFalse(newer.isBefore(older));
            if (newer.equals(older)) {
                assertTrue(all.get(i - 1).get("id").asLong() > all.get(i).get("id").asLong());
            }
        }

        assertEquals(400, status(bearer(get("/activity/me?size=101"), token)));
        assertEquals(400, status(bearer(get("/activity/me?size=0"), token)));
        assertEquals(400, status(bearer(get("/activity/me?page=-1"), token)));
        assertEquals(400, status(as(get("/admin/activity?size=500"), admin)));
        assertEquals(20, body(perform(bearer(get("/activity/me"), token))).get("size").asInt());
    }

    @Test
    void activityResponses_neverContainSecrets() throws Exception {
        String token = login(customerA, PASSWORD);
        perform(bearer(json(patch("/account/me/password"), "{\"currentPassword\":\"" + PASSWORD
                + "\",\"newPassword\":\"Brandnew789\",\"confirmNewPassword\":\"Brandnew789\"}"), token));
        String upi = onlineOrder(customerB, "UPI", 1);
        tieToRazorpay(upi, "order_sec_" + s);
        String sig = signature("order_sec_" + s, "pay_sec_" + s);
        perform(json(as(post("/payments/verify"), customerB), verifyBody(upi, "order_sec_" + s, "pay_sec_" + s, sig)));

        String hash = userRepository.findById(customerA.getId()).orElseThrow().getPassword();
        for (String raw : new String[]{
                perform(as(get("/admin/activity?size=100"), admin)).getResponse().getContentAsString(),
                perform(bearer(get("/activity/me"), login(customerA, "Brandnew789"))).getResponse().getContentAsString()}) {
            String lower = raw.toLowerCase();
            assertFalse(raw.contains(PASSWORD));
            assertFalse(raw.contains("Brandnew789"));
            assertFalse(raw.contains(hash));
            assertFalse(raw.contains(sig));
            assertFalse(raw.contains("pay_sec_" + s));
            assertFalse(raw.contains(razorpayKeySecret));
            assertFalse(lower.contains("password\""));
            assertFalse(lower.contains("tokenversion"));
            assertFalse(lower.contains("signature"));
            assertFalse(raw.contains("Bearer "));
        }
    }

    // =====================================================================================
    // Recording: accounts
    // =====================================================================================

    @Test
    void registrationAndLogin_areRecorded_withTheAccountAsActor() throws Exception {
        String email = "reg-" + s + "@example.com";
        assertEquals(201, status(json(post("/register"), "{\"name\":\"Reggie\",\"email\":\"" + email
                + "\",\"mobile\":\"" + TestMobiles.next() + "\",\"password\":\"" + PASSWORD
                + "\",\"actorUserId\":\"" + admin.getUserId() + "\",\"actorRole\":\"ROLE_ADMIN\","
                + "\"details\":{\"password\":\"leak\"}}")));
        UserEntity registered = userRepository.findByEmail(email).orElseThrow();

        JsonNode registeredEvent = single(registered, AuditAction.ACCOUNT_REGISTERED);
        assertActor(registeredEvent, registered);
        assertEquals("ROLE_USER", registeredEvent.get("actorRole").asText());
        assertEquals("ACCOUNT", registeredEvent.get("targetType").asText());
        assertEquals(registered.getUserId(), registeredEvent.get("targetId").asText());
        assertEquals(0, registeredEvent.get("details").size());
        // the client-supplied actor fields went nowhere
        assertTrue(ofAction(eventsOf(admin), AuditAction.ACCOUNT_REGISTERED).isEmpty());

        login(registered, PASSWORD);
        JsonNode loginEvent = single(registered, AuditAction.AUTH_LOGIN_SUCCESS);
        assertActor(loginEvent, registered);
        assertEquals(registered.getUserId(), loginEvent.get("targetId").asText());
    }

    @Test
    void failedLogins_andFailedRegistrations_recordNothing() throws Exception {
        assertEquals(401, status(json(post("/login"),
                "{\"identifier\":\"" + customerA.getEmail() + "\",\"password\":\"Wrong12345\"}")));
        assertEquals(409, status(json(post("/register"), "{\"name\":\"Dup\",\"email\":\"" + customerA.getEmail()
                + "\",\"mobile\":\"" + TestMobiles.next() + "\",\"password\":\"" + PASSWORD + "\"}")));

        assertTrue(eventsOf(customerA).isEmpty());
    }

    @Test
    void profileUpdateAndPasswordChange_areRecorded_withoutValues_andTokenRevocationStillWorks() throws Exception {
        String token = login(customerA, PASSWORD);
        String newMobile = TestMobiles.next();

        assertEquals(200, perform(bearer(json(patch("/account/me"), "{\"name\":\"Aaron Renamed\",\"email\":\""
                + customerA.getEmail() + "\",\"mobile\":\"" + newMobile + "\",\"actorUserId\":\"" + customerB.getUserId()
                + "\"}"), token)).getResponse().getStatus());
        JsonNode profile = single(customerA, AuditAction.PROFILE_UPDATED);
        assertEquals(customerA.getUserId(), profile.get("actorUserId").asText());
        assertEquals("ACCOUNT", profile.get("targetType").asText());
        List<String> changed = new ArrayList<>();
        profile.get("details").get("changedFields").forEach(f -> changed.add(f.asText()));
        assertEquals(List.of("name", "mobile"), changed);
        // details carry field NAMES only - no old/new values (the actor name is just the actor snapshot)
        String details = profile.get("details").toString();
        assertFalse(details.contains("Aaron"));
        assertFalse(profile.toString().contains(newMobile));
        assertFalse(profile.toString().contains(customerA.getMobile()));
        assertTrue(eventsOf(customerB).isEmpty());

        // a save that changes nothing is not an event
        perform(bearer(json(patch("/account/me"), "{\"name\":\"Aaron Renamed\",\"email\":\""
                + customerA.getEmail() + "\",\"mobile\":\"" + newMobile + "\"}"), token));
        assertEquals(1, ofAction(eventsOf(customerA), AuditAction.PROFILE_UPDATED).size());

        assertEquals(204, perform(bearer(json(patch("/account/me/password"), "{\"currentPassword\":\"" + PASSWORD
                + "\",\"newPassword\":\"Brandnew789\",\"confirmNewPassword\":\"Brandnew789\"}"), token)).getResponse().getStatus());
        JsonNode changedPassword = single(customerA, AuditAction.PASSWORD_CHANGED);
        assertEquals(0, changedPassword.get("details").size());
        // A5 token revocation still happens
        assertEquals(401, status(bearer(get("/activity/me"), token)));

        // a wrong current password changes nothing and records nothing
        String fresh = login(customerA, "Brandnew789");
        perform(bearer(json(patch("/account/me/password"), "{\"currentPassword\":\"Wrong12345\",\"newPassword\":\"Other7890\","
                + "\"confirmNewPassword\":\"Other7890\"}"), fresh));
        assertEquals(1, ofAction(eventsOf(customerA), AuditAction.PASSWORD_CHANGED).size());
    }

    // =====================================================================================
    // Recording: cashier management
    // =====================================================================================

    @Test
    void cashierManagement_isRecorded_withTheAdminAsActor_andTheCashierAsTarget() throws Exception {
        String email = "newcash-" + s + "@example.com";
        MvcResult created = perform(json(as(post("/admin/cashiers"), admin), "{\"name\":\"New Cashier\",\"email\":\""
                + email + "\",\"mobile\":\"" + TestMobiles.next() + "\",\"password\":\"" + PASSWORD + "\"}"));
        assertEquals(201, created.getResponse().getStatus());
        String newCashierId = body(created).get("userId").asText();
        String cashierToken = login(cashierA, PASSWORD);

        assertEquals(204, status(json(as(post("/admin/cashiers/" + cashierA.getUserId() + "/reset-password"), admin),
                "{\"password\":\"Fresh4567\"}")));
        assertEquals(200, status(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin), "{\"enabled\":false}")));
        // repeating the same status is not a new event
        assertEquals(200, status(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin), "{\"enabled\":false}")));
        assertEquals(200, status(json(as(patch("/admin/cashiers/" + cashierA.getUserId() + "/status"), admin), "{\"enabled\":true}")));

        List<JsonNode> adminEvents = eventsOf(admin);
        JsonNode createdEvent = single(admin, AuditAction.CASHIER_CREATED);
        assertActor(createdEvent, admin);
        assertEquals("CASHIER", createdEvent.get("targetType").asText());
        assertEquals(newCashierId, createdEvent.get("targetId").asText());

        JsonNode reset = single(admin, AuditAction.CASHIER_PASSWORD_RESET);
        assertEquals(cashierA.getUserId(), reset.get("targetId").asText());
        assertFalse(reset.toString().contains("Fresh4567"));

        JsonNode deactivated = single(admin, AuditAction.CASHIER_DEACTIVATED);
        assertEquals(cashierA.getUserId(), deactivated.get("targetId").asText());
        assertEquals("ACTIVE", deactivated.get("details").get("from").asText());
        assertEquals("INACTIVE", deactivated.get("details").get("to").asText());
        JsonNode reactivated = single(admin, AuditAction.CASHIER_REACTIVATED);
        assertEquals(cashierA.getUserId(), reactivated.get("targetId").asText());
        // newest first: reactivation after deactivation after reset
        assertEquals(List.of("CASHIER_REACTIVATED", "CASHIER_DEACTIVATED", "CASHIER_PASSWORD_RESET", "CASHIER_CREATED"),
                adminEvents.stream().map(e -> e.get("action").asText()).toList());

        // the events changed nothing about token revocation
        assertEquals(401, status(bearer(get("/activity/me"), cashierToken)));
        // and they are the admin's actions, not the cashier's
        assertTrue(eventsOf(cashierA).stream().noneMatch(e -> e.get("action").asText().startsWith("CASHIER_")));
    }

    @Test
    void failedCashierCreation_recordsNothing() throws Exception {
        assertEquals(409, status(json(as(post("/admin/cashiers"), admin), "{\"name\":\"Dup\",\"email\":\""
                + customerA.getEmail() + "\",\"mobile\":\"" + TestMobiles.next() + "\",\"password\":\"" + PASSWORD + "\"}")));
        assertEquals(400, status(json(as(post("/admin/cashiers"), admin), "{}")));

        assertTrue(eventsOf(admin).isEmpty());
    }

    // =====================================================================================
    // Recording: orders and payments
    // =====================================================================================

    @Test
    void onlineAndPosCreation_recordExactlyOneEvent_withTheRightActor() throws Exception {
        String online = onlineOrder(customerA, "CASH", 2);
        String pos = posSale(cashierA, customerA, "CASH");
        String walkIn = posSale(cashierA, null, "UPI");

        JsonNode onlineEvent = single(customerA, AuditAction.ONLINE_ORDER_CREATED);
        assertActor(onlineEvent, customerA);
        assertEquals("ORDER", onlineEvent.get("targetType").asText());
        assertEquals(online, onlineEvent.get("targetId").asText());
        assertEquals("ONLINE", onlineEvent.get("details").get("salesChannel").asText());
        assertEquals(20.2, onlineEvent.get("details").get("grandTotal").asDouble(), 0.0001);

        // POS: the actor is the cashier (createdBy), never the linked customer (user)
        List<JsonNode> posEvents = ofAction(eventsOf(cashierA), AuditAction.POS_ORDER_CREATED);
        assertEquals(2, posEvents.size());
        assertEquals(walkIn, posEvents.get(0).get("targetId").asText());
        assertEquals("WALK_IN", posEvents.get(0).get("details").get("customer").asText());
        assertEquals("PENDING_PAYMENT", posEvents.get(0).get("details").get("orderStatus").asText());
        assertEquals(pos, posEvents.get(1).get("targetId").asText());
        assertEquals("REGISTERED", posEvents.get(1).get("details").get("customer").asText());
        assertActor(posEvents.get(1), cashierA);
        assertEquals(1, ofAction(eventsOf(customerA), AuditAction.ONLINE_ORDER_CREATED).size());
        assertTrue(ofAction(eventsOf(customerA), AuditAction.POS_ORDER_CREATED).isEmpty());
        // no customer identity copied into the event
        assertFalse(posEvents.get(1).toString().contains(customerA.getName()));
        assertFalse(posEvents.get(1).toString().contains(customerA.getMobile()));
    }

    @Test
    void idempotentReplay_doesNotRecordASecondCreation() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}";

        assertEquals(201, status(json(as(post("/orders"), customerA), body).header("Idempotency-Key", key)));
        assertEquals(200, status(json(as(post("/orders"), customerA), body).header("Idempotency-Key", key)));

        assertEquals(1, ofAction(eventsOf(customerA), AuditAction.ONLINE_ORDER_CREATED).size());
    }

    @Test
    void failedOrderCreation_recordsNothing() throws Exception {
        String tooMany = "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1000}]}";
        assertEquals(409, status(json(as(post("/orders"), customerA), tooMany)));
        assertEquals(404, status(json(as(post("/orders"), customerA),
                "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"ghost\",\"quantity\":1}]}")));
        assertEquals(409, status(json(as(post("/pos/orders"), cashierA), tooMany)));
        // an admin is not allowed to create POS orders at all
        assertEquals(403, status(json(as(post("/pos/orders"), admin), tooMany.replace("1000", "1"))));

        assertTrue(eventsOf(customerA).isEmpty());
        assertTrue(eventsOf(cashierA).isEmpty());
        assertTrue(eventsOf(admin).isEmpty());
        assertEquals(0, orderEntityRepository.count());
    }

    @Test
    void paymentVerification_isRecordedOnce_andAFailedVerificationRecordsNothing() throws Exception {
        String orderId = onlineOrder(customerA, "UPI", 1);
        tieToRazorpay(orderId, "order_v_" + s);

        // bad signature: rejected, order untouched, no event
        assertEquals(400, status(json(as(post("/payments/verify"), customerA),
                verifyBody(orderId, "order_v_" + s, "pay_v_" + s, "deadbeef"))));
        assertTrue(ofAction(eventsOf(customerA), AuditAction.PAYMENT_VERIFIED).isEmpty());

        String good = verifyBody(orderId, "order_v_" + s, "pay_v_" + s, signature("order_v_" + s, "pay_v_" + s));
        assertEquals(200, status(json(as(post("/payments/verify"), customerA), good)));
        // an idempotent replay of the same verification is not a second event
        assertEquals(200, status(json(as(post("/payments/verify"), customerA), good)));

        JsonNode verified = single(customerA, AuditAction.PAYMENT_VERIFIED);
        assertEquals(orderId, verified.get("targetId").asText());
        assertEquals("PAID", verified.get("details").get("orderStatus").asText());
        assertEquals("UPI", verified.get("details").get("paymentMethod").asText());
    }

    @Test
    void paymentFailureAndCancellation_areRecorded_onlyForRealTransitions() throws Exception {
        String failed = onlineOrder(customerA, "UPI", 1);
        String cancelled = posSale(cashierA, null, "UPI");

        assertEquals(200, status(as(post("/orders/" + failed + "/fail-payment"), customerA)));
        assertEquals(200, status(as(post("/orders/" + cancelled + "/cancel"), cashierA)));
        // repeated / invalid transitions are rejected and record nothing more
        assertEquals(409, status(as(post("/orders/" + failed + "/fail-payment"), customerA)));
        assertEquals(409, status(as(post("/orders/" + failed + "/cancel"), customerA)));
        assertEquals(409, status(as(post("/orders/" + cancelled + "/cancel"), cashierA)));
        // someone else's order: refused, nothing recorded for them
        assertEquals(403, status(as(post("/orders/" + failed + "/cancel"), customerB)));

        JsonNode failEvent = single(customerA, AuditAction.PAYMENT_FAILED);
        assertEquals(failed, failEvent.get("targetId").asText());
        assertEquals("PAYMENT_FAILED", failEvent.get("details").get("orderStatus").asText());
        assertTrue(ofAction(eventsOf(customerA), AuditAction.ORDER_CANCELLED).isEmpty());

        JsonNode cancelEvent = single(cashierA, AuditAction.ORDER_CANCELLED);
        assertEquals(cancelled, cancelEvent.get("targetId").asText());
        assertActor(cancelEvent, cashierA);
        assertEquals("POS", cancelEvent.get("details").get("salesChannel").asText());
        assertTrue(eventsOf(customerB).isEmpty());
    }

    @Test
    void expiredReservation_isRecordedAsASystemPaymentFailure() throws Exception {
        String stale = onlineOrder(customerA, "UPI", 3);
        OrderEntity order = orderEntityRepository.findByOrderId(stale).orElseThrow();
        order.setCreatedAt(LocalDateTime.now().minusMinutes(31));
        orderEntityRepository.save(order);

        onlineOrder(customerB, "CASH", 1);   // touches the same item -> lazily expires the stale order

        List<JsonNode> system = systemActivity("actorRole=SYSTEM&action=PAYMENT_FAILED&size=100").stream()
                .filter(e -> stale.equals(e.get("targetId").asText())).toList();
        assertEquals(1, system.size());
        assertTrue(system.get(0).get("actorUserId").isNull());
        assertEquals("RESERVATION_EXPIRED", system.get(0).get("details").get("reason").asText());
        // not attributed to the customer whose checkout triggered it
        assertTrue(ofAction(eventsOf(customerB), AuditAction.PAYMENT_FAILED).isEmpty());
    }

    // =====================================================================================
    // Recording: catalog and inventory
    // =====================================================================================

    @Test
    void itemCategoryAndInventoryChanges_areRecorded_andFailuresAreNot() throws Exception {
        MockMultipartFile image = new MockMultipartFile("file", "x.png", "image/png", new byte[]{1, 2, 3});

        MvcResult newCategory = perform(multipart("/admin/categories")
                .file(image)
                .file(new MockMultipartFile("category", "", "application/json",
                        ("{\"name\":\"Audit Cat " + s + "\",\"bgColor\":\"#fff\"}").getBytes(StandardCharsets.UTF_8)))
                .with(user(admin.getEmail()).roles("ADMIN")));
        assertEquals(201, newCategory.getResponse().getStatus(), newCategory.getResponse().getContentAsString());
        String categoryId = body(newCategory).get("categoryId").asText();

        MvcResult newItem = perform(multipart("/admin/items")
                .file(image)
                .file(new MockMultipartFile("item", "", "application/json",
                        ("{\"name\":\"Audit Item " + s + "\",\"price\":25,\"categoryId\":\"" + categoryId
                                + "\",\"stockQuantity\":7}").getBytes(StandardCharsets.UTF_8)))
                .with(user(admin.getEmail()).roles("ADMIN")));
        assertEquals(201, newItem.getResponse().getStatus(), newItem.getResponse().getContentAsString());
        String itemId = body(newItem).get("itemId").asText();

        assertEquals(200, status(json(as(put("/admin/items/" + itemId), admin), "{\"price\":30,\"active\":false}")));
        assertEquals(200, status(json(as(patch("/admin/items/" + itemId + "/stock"), admin), "{\"delta\":-2}")));
        // failed adjustment (would go below zero/reserved): rejected, not recorded
        assertEquals(409, status(json(as(patch("/admin/items/" + itemId + "/stock"), admin), "{\"delta\":-100}")));
        // deleting a category that still has items fails, and is not recorded
        assertEquals(409, status(as(delete("/admin/categories/" + categoryId), admin)));
        assertEquals(204, status(as(delete("/admin/items/" + itemId), admin)));
        assertEquals(204, status(as(delete("/admin/categories/" + categoryId), admin)));

        List<JsonNode> events = eventsOf(admin);
        assertEquals(List.of("CATEGORY_DELETED", "ITEM_DELETED", "INVENTORY_ADJUSTED", "ITEM_UPDATED",
                        "ITEM_CREATED", "CATEGORY_CREATED"),
                events.stream().map(e -> e.get("action").asText()).toList());
        events.forEach(e -> assertActor(e, admin));

        JsonNode adjusted = single(admin, AuditAction.INVENTORY_ADJUSTED);
        assertEquals("ITEM", adjusted.get("targetType").asText());
        assertEquals(itemId, adjusted.get("targetId").asText());
        assertEquals(-2, adjusted.get("details").get("delta").asInt());
        assertEquals(5, adjusted.get("details").get("stockQuantity").asInt());

        List<String> fields = new ArrayList<>();
        single(admin, AuditAction.ITEM_UPDATED).get("details").get("fields").forEach(f -> fields.add(f.asText()));
        assertEquals(List.of("price", "active"), fields);
        assertEquals(categoryId, single(admin, AuditAction.CATEGORY_CREATED).get("targetId").asText());
        assertEquals(categoryId, single(admin, AuditAction.CATEGORY_DELETED).get("targetId").asText());
    }

    @Test
    void readsAndBrowsing_recordNothing() throws Exception {
        perform(as(get("/items"), customerA));
        perform(as(get("/categories"), customerA));
        perform(as(get("/orders/my-orders"), customerA));
        perform(as(get("/account/me"), customerA));
        perform(as(get("/activity/me"), customerA));
        perform(as(get("/admin/orders"), admin));
        perform(as(get("/admin/cashiers"), admin));
        perform(as(get("/admin/activity"), admin));

        assertTrue(eventsOf(customerA).isEmpty());
        assertTrue(eventsOf(admin).isEmpty());
    }

    // =====================================================================================
    // Write-once guarantees and rollback semantics
    // =====================================================================================

    @Test
    void noApiCanModifyOrDeleteAuditRecords() throws Exception {
        login(customerA, PASSWORD);
        long id = eventsOf(customerA).get(0).get("id").asLong();

        assertEquals(404, status(as(delete("/admin/activity/" + id), admin)));
        assertEquals(404, status(json(as(put("/admin/activity/" + id), admin), "{\"action\":\"ACCOUNT_REGISTERED\"}")));
        assertEquals(404, status(json(as(patch("/admin/activity/" + id), admin), "{\"action\":\"ACCOUNT_REGISTERED\"}")));
        assertEquals(405, status(as(delete("/admin/activity"), admin)));
        assertEquals(405, status(json(as(post("/admin/activity"), admin), "{\"action\":\"PROFILE_UPDATED\"}")));
        assertEquals(405, status(json(as(post("/activity/me"), customerA), "{\"action\":\"PROFILE_UPDATED\"}")));
        assertEquals(405, status(as(delete("/activity/me"), customerA)));

        List<JsonNode> after = eventsOf(customerA);
        assertEquals(1, after.size());
        assertEquals(id, after.get(0).get("id").asLong());
        assertEquals("AUTH_LOGIN_SUCCESS", after.get(0).get("action").asText());
    }

    @Test
    void auditPersistenceLayer_exposesNoUpdateOrDeleteOperation() {
        for (Method method : AuditLogRepository.class.getMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.startsWith("delete") || name.startsWith("remove") || name.startsWith("update")
                    || name.contains("modify"), "unexpected repository method: " + method.getName());
        }
        for (Method method : AuditLogEntity.class.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set") && Modifier.isPublic(method.getModifiers()),
                    "audit entity must have no public setters: " + method.getName());
        }
        assertNotNull(AuditLogEntity.class.getAnnotation(org.hibernate.annotations.Immutable.class));
    }

    @Test
    void anEventRecordedInsideARolledBackTransaction_isRolledBackWithIt() throws Exception {
        UserEntity actor = userRepository.findById(customerA.getId()).orElseThrow();

        transactionTemplate.executeWithoutResult(status -> {
            auditService.recordFor(actor, AuditAction.PROFILE_UPDATED, AuditTargetType.ACCOUNT, actor.getUserId(),
                    Map.of("changedFields", List.of("name")));
            status.setRollbackOnly();   // the business operation failed after recording
        });
        assertTrue(eventsOf(customerA).isEmpty());

        transactionTemplate.executeWithoutResult(status ->
                auditService.recordFor(actor, AuditAction.PROFILE_UPDATED, AuditTargetType.ACCOUNT, actor.getUserId(),
                        Map.of("changedFields", List.of("name"))));
        assertEquals(1, eventsOf(customerA).size());
    }

    @Test
    void sensitiveLookingMetadataKeys_areNeverStored() throws Exception {
        UserEntity actor = userRepository.findById(customerA.getId()).orElseThrow();

        auditService.recordFor(actor, AuditAction.PROFILE_UPDATED, AuditTargetType.ACCOUNT, actor.getUserId(),
                Map.of("password", "p", "newPasswordHash", "h", "jwtToken", "t", "tokenVersion", 3,
                        "razorpaySignature", "sig", "keySecret", "x", "Authorization", "Bearer y",
                        "changedFields", List.of("name")));

        JsonNode event = single(customerA, AuditAction.PROFILE_UPDATED);
        JsonNode details = event.get("details");
        assertEquals(1, details.size(), details.toString());
        assertEquals("name", details.get("changedFields").get(0).asText());
    }
}
