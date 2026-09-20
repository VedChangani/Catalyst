package in.vedchangani.billingsoftware.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Day 4 / Batch 13: Idempotency-Key on POST /orders and POST /pos/orders, through the real
 * security chain, controllers, services and the H2 "test" database. Deliberately NOT
 * @Transactional: the service transactions must commit / roll back for real.
 *
 * H2 does not reproduce MySQL/InnoDB locking, so the concurrency test proves the invariants
 * (one order, one stock movement) on H2 only; it does not prove InnoDB's exact interleaving.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotentOrderCreationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderService orderService;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    private UserEntity customerA;
    private UserEntity customerB;
    private UserEntity cashier;
    private UserEntity otherCashier;
    private UserEntity admin;
    private ItemEntity item;
    private ItemEntity otherItem;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        customerA = aUser("Aaron Customer", "aaron-" + s + "@example.com", "ROLE_USER");
        customerB = aUser("Bella Customer", "bella-" + s + "@example.com", "ROLE_USER");
        cashier = aUser("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        otherCashier = aUser("Drew Cashier", "drew-" + s + "@example.com", "ROLE_CASHIER");
        admin = aUser("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("idem-cat-" + s).name("Idempotency " + s).build());
        item = anItem("idem-item-" + s, category);
        otherItem = anItem("idem-other-" + s, category);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity aUser(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).password("not-used")
                .role(role).name(name).mobile(in.vedchangani.billingsoftware.TestMobiles.next()).build());
    }

    private ItemEntity anItem(String itemId, CategoryEntity category) {
        return itemRepository.save(ItemEntity.builder()
                .itemId(itemId).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(100).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private String cart(ItemEntity target, int quantity) {
        return "[{\"itemId\":\"" + target.getItemId() + "\",\"quantity\":" + quantity + "}]";
    }

    private String onlineBody(String method, int quantity) {
        return "{\"paymentMethod\":\"" + method
                + "\",\"cartItems\":" + cart(item, quantity) + "}";
    }

    private String posBody(String method, int quantity, String customerUserId) {
        String customerPart = customerUserId == null ? "" : "\"customerUserId\":\"" + customerUserId + "\",";
        return "{" + customerPart + "\"paymentMethod\":\"" + method + "\",\"cartItems\":" + cart(item, quantity) + "}";
    }

    private MvcResult send(String url, UserEntity actor, String role, String body, String idempotencyKey) throws Exception {
        var request = post(url).with(user(actor.getEmail()).roles(role))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult online(UserEntity actor, String body, String idempotencyKey) throws Exception {
        return send("/orders", actor, "USER", body, idempotencyKey);
    }

    private MvcResult pos(UserEntity actor, String role, String body, String idempotencyKey) throws Exception {
        return send("/pos/orders", actor, role, body, idempotencyKey);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void authenticateAs(UserEntity actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }

    private ItemEntity reloadItem() {
        return itemRepository.findByItemId(item.getItemId()).orElseThrow();
    }

    private void assertStock(int stock, int reserved) {
        assertEquals(stock, reloadItem().getStockQuantity());
        assertEquals(reserved, reloadItem().getReservedQuantity());
    }

    private OrderEntity storedOrder(String orderId) {
        return orderEntityRepository.findByOrderId(orderId).orElseThrow();
    }

    // ---- same key, same request ----

    @Test
    void online_sameKeySameRequest_returnsTheSameOrderWith200_andReservesOnce() throws Exception {
        String key = key();

        MvcResult first = online(customerA, onlineBody("UPI", 2), key);
        MvcResult second = online(customerA, onlineBody("UPI", 2), key);

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(json(first).get("orderId").asText(), json(second).get("orderId").asText());
        assertEquals("PENDING_PAYMENT", json(second).get("orderStatus").asText());
        assertEquals("ONLINE", json(second).get("salesChannel").asText());
        assertEquals(1, orderEntityRepository.count());
        assertStock(100, 2); // reserved once, not twice
    }

    @Test
    void online_cash_sameKey_commitsStockOnce() throws Exception {
        String key = key();

        MvcResult first = online(customerA, onlineBody("CASH", 2), key);
        MvcResult second = online(customerA, onlineBody("CASH", 2), key);

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(json(first).get("orderId").asText(), json(second).get("orderId").asText());
        assertEquals(1, orderEntityRepository.count());
        assertStock(98, 0);
    }

    @Test
    void pos_cash_sameKey_returnsTheSameOrderWith200_andCommitsStockOnce() throws Exception {
        String key = key();

        MvcResult first = pos(cashier, "CASHIER", posBody("CASH", 2, null), key);
        MvcResult second = pos(cashier, "CASHIER", posBody("CASH", 2, null), key);

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(json(first).get("orderId").asText(), json(second).get("orderId").asText());
        assertEquals("POS", json(second).get("salesChannel").asText());
        assertEquals(cashier.getUserId(), json(second).get("createdBy").get("userId").asText());
        assertEquals(1, orderEntityRepository.count());
        assertStock(98, 0);
    }

    @Test
    void pos_registeredCustomer_sameKey_replaysWithSameCustomerAndCreator() throws Exception {
        String key = key();
        String body = posBody("UPI", 1, customerA.getUserId());

        String orderId = json(pos(cashier, "CASHIER", body, key)).get("orderId").asText();
        MvcResult replay = pos(cashier, "CASHIER", body, key);

        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(orderId, json(replay).get("orderId").asText());
        OrderEntity stored = storedOrder(orderId);
        assertEquals(customerA.getId(), stored.getUser().getId());
        assertEquals(cashier.getId(), stored.getCreatedBy().getId());
        assertEquals(1, orderEntityRepository.count());
        assertStock(100, 1);
    }

    @Test
    void replay_ignoresLineOrderAndDuplicateLineSplitting() throws Exception {
        String key = key();
        String one = "{\"customerName\":\"Aaron\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"CASH\",\"cartItems\":["
                + "{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1},"
                + "{\"itemId\":\"" + otherItem.getItemId() + "\",\"quantity\":3}]}";
        // same logical cart: lines reordered, first item's quantity split across two lines
        String same = "{\"phoneNumber\":\"9999999999\",\"customerName\":\"Aaron\",\"paymentMethod\":\"CASH\",\"cartItems\":["
                + "{\"itemId\":\"" + otherItem.getItemId() + "\",\"quantity\":3},"
                + "{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}";

        assertEquals(201, online(customerA, one, key).getResponse().getStatus());
        assertEquals(200, online(customerA, same, key).getResponse().getStatus());
        assertEquals(1, orderEntityRepository.count());
    }

    // ---- same key, different request ----

    @Test
    void sameKey_differentQuantity_isConflict_andNothingChanges() throws Exception {
        String key = key();
        assertEquals(201, online(customerA, onlineBody("UPI", 2), key).getResponse().getStatus());

        MvcResult conflict = online(customerA, onlineBody("UPI", 5), key);

        assertEquals(409, conflict.getResponse().getStatus());
        assertTrue(json(conflict).get("message").asText().contains("Idempotency-Key"));
        assertEquals(1, orderEntityRepository.count());
        assertStock(100, 2);
    }

    @Test
    void sameKey_differentPaymentMethodOrItem_isConflict() throws Exception {
        String key = key();
        assertEquals(201, online(customerA, onlineBody("UPI", 2), key).getResponse().getStatus());

        assertEquals(409, online(customerA, onlineBody("CASH", 2), key).getResponse().getStatus());
        // identity fields are not part of the ONLINE request any more: they are ignored, so the same
        // logical request with a different name/phone is a plain replay, not a different request
        assertEquals(200, online(customerA,
                onlineBody("UPI", 2).replaceFirst("\\{", "{\"customerName\":\"Someone Else\",\"phoneNumber\":\"8888888888\","), key)
                .getResponse().getStatus());
        assertEquals(409, online(customerA,
                onlineBody("UPI", 2).replace(item.getItemId(), otherItem.getItemId()), key).getResponse().getStatus());

        assertEquals(1, orderEntityRepository.count());
        assertStock(100, 2);
    }

    @Test
    void pos_sameKey_differentCustomerSelection_isConflict() throws Exception {
        String key = key();
        assertEquals(201, pos(cashier, "CASHIER", posBody("UPI", 1, customerA.getUserId()), key).getResponse().getStatus());

        // another registered customer, and a walk-in, are both materially different requests
        assertEquals(409, pos(cashier, "CASHIER", posBody("UPI", 1, customerB.getUserId()), key).getResponse().getStatus());
        assertEquals(409, pos(cashier, "CASHIER", posBody("UPI", 1, null), key).getResponse().getStatus());

        assertEquals(1, orderEntityRepository.count());
        assertStock(100, 1);
    }

    @Test
    void pos_walkInKey_cannotBeReusedToPickACustomer() throws Exception {
        String key = key();
        assertEquals(201, pos(cashier, "CASHIER", posBody("CASH", 1, null), key).getResponse().getStatus());

        assertEquals(409, pos(cashier, "CASHIER", posBody("CASH", 1, customerA.getUserId()), key).getResponse().getStatus());

        assertEquals(1, orderEntityRepository.count());
        assertNull(orderEntityRepository.findAll().get(0).getUser());
    }

    // ---- same key, different actor / channel ----

    @Test
    void online_sameKey_differentCustomer_isConflict() throws Exception {
        String key = key();
        String orderId = json(online(customerA, onlineBody("UPI", 2), key)).get("orderId").asText();

        MvcResult other = online(customerB, onlineBody("UPI", 2), key);

        assertEquals(409, other.getResponse().getStatus());
        assertFalse(other.getResponse().getContentAsString().contains(orderId)); // nothing leaked
        assertEquals(1, orderEntityRepository.count());
        assertStock(100, 2);
    }

    @Test
    void pos_sameKey_differentStaffMember_isConflict() throws Exception {
        String key = key();
        String orderId = json(pos(cashier, "CASHIER", posBody("CASH", 1, null), key)).get("orderId").asText();

        MvcResult byOtherCashier = pos(otherCashier, "CASHIER", posBody("CASH", 1, null), key);
        MvcResult byAdmin = pos(admin, "ADMIN", posBody("CASH", 1, null), key);

        assertEquals(409, byOtherCashier.getResponse().getStatus());
        assertEquals(403, byAdmin.getResponse().getStatus()); // an admin cannot create POS sales at all
        assertFalse(byOtherCashier.getResponse().getContentAsString().contains(orderId));
        assertEquals(1, orderEntityRepository.count());
        assertStock(99, 0);
    }

    @Test
    void sameKey_acrossChannels_isConflict() throws Exception {
        String key = key();
        assertEquals(201, online(customerA, onlineBody("CASH", 1), key).getResponse().getStatus());

        assertEquals(403, pos(admin, "ADMIN", posBody("CASH", 1, null), key).getResponse().getStatus());

        assertEquals(1, orderEntityRepository.count());
    }

    // ---- different keys / no key ----

    @Test
    void differentKeys_createTwoOrders() throws Exception {
        MvcResult first = online(customerA, onlineBody("CASH", 2), key());
        MvcResult second = online(customerA, onlineBody("CASH", 2), key());

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, second.getResponse().getStatus());
        assertNotEquals(json(first).get("orderId").asText(), json(second).get("orderId").asText());
        assertEquals(2, orderEntityRepository.count());
        assertStock(96, 0);
    }

    @Test
    void noKey_behavesAsBefore_everyRequestCreatesAnOrder() throws Exception {
        MvcResult first = online(customerA, onlineBody("CASH", 2), null);
        MvcResult second = online(customerA, onlineBody("CASH", 2), null);
        MvcResult posOrder = pos(cashier, "CASHIER", posBody("CASH", 1, null), null);

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, second.getResponse().getStatus());
        assertEquals(201, posOrder.getResponse().getStatus());
        assertEquals(3, orderEntityRepository.count());
        assertStock(95, 0);
        orderEntityRepository.findAll().forEach(order -> {
            assertNull(order.getIdempotencyKey());
            assertNull(order.getIdempotencyFingerprint());
        });
    }

    // ---- key validation ----

    @Test
    void invalidKeys_areRejectedBeforeAnythingIsCreated() throws Exception {
        for (String bad : new String[]{"a".repeat(65), "", " ", "has space", "semi;colon", "quote\"d", "ключ"}) {
            assertEquals(400, online(customerA, onlineBody("CASH", 1), bad).getResponse().getStatus(), "key: [" + bad + "]");
        }
        assertEquals(0, orderEntityRepository.count());
        assertStock(100, 0);

        // the longest allowed key works
        assertEquals(201, online(customerA, onlineBody("CASH", 1), "k".repeat(64)).getResponse().getStatus());
    }

    // ---- replay after the order changed state ----

    @Test
    void replay_afterCancel_returnsTheCancelledOrder_withoutReservingAgain() throws Exception {
        String key = key();
        String orderId = json(online(customerA, onlineBody("UPI", 2), key)).get("orderId").asText();
        assertStock(100, 2);
        authenticateAs(customerA);
        orderService.cancelOrder(orderId);
        assertStock(100, 0);

        MvcResult replay = online(customerA, onlineBody("UPI", 2), key);

        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(orderId, json(replay).get("orderId").asText());
        assertEquals("CANCELLED", json(replay).get("orderStatus").asText());
        assertEquals(1, orderEntityRepository.count());
        assertStock(100, 0); // still nothing reserved: the replay did not restart anything
    }

    @Test
    void replay_afterPaymentFailure_returnsPaymentFailedOrder_andChangesNothing() throws Exception {
        String key = key();
        String body = posBody("UPI", 1, null);
        String orderId = json(pos(cashier, "CASHIER", body, key)).get("orderId").asText();
        authenticateAs(cashier);
        orderService.failPayment(orderId);

        MvcResult replay = pos(cashier, "CASHIER", body, key);

        assertEquals(200, replay.getResponse().getStatus());
        assertEquals("PAYMENT_FAILED", json(replay).get("orderStatus").asText());
        assertEquals("FAILED", json(replay).get("paymentStatus").asText());
        assertStock(100, 0);
        assertEquals(1, orderEntityRepository.count());
    }

    @Test
    void replay_afterVerifiedPayment_returnsPaidOrder_keepsPaidAt_andStock() throws Exception {
        String key = key();
        String orderId = json(online(customerA, onlineBody("UPI", 2), key)).get("orderId").asText();
        OrderEntity order = storedOrder(orderId);
        order.getPaymentDetails().setRazorpayOrderId("order_idem_" + UUID.randomUUID());
        orderEntityRepository.save(order);
        String razorpayOrderId = storedOrder(orderId).getPaymentDetails().getRazorpayOrderId();
        authenticateAs(customerA);
        orderService.verifyPayment(verification(orderId, razorpayOrderId, "pay_idem_1"));
        var paidAt = storedOrder(orderId).getPaymentDetails().getPaidAt();
        assertNotNull(paidAt);
        assertStock(98, 0);

        MvcResult replay = online(customerA, onlineBody("UPI", 2), key);

        assertEquals(200, replay.getResponse().getStatus());
        assertEquals("PAID", json(replay).get("orderStatus").asText());
        assertEquals(paidAt, storedOrder(orderId).getPaymentDetails().getPaidAt());
        assertStock(98, 0);
        assertEquals(1, orderEntityRepository.count());
    }

    @Test
    void replay_whenTheItemBecameUnavailable_stillReturnsTheOriginalOrder() throws Exception {
        String key = key();
        String orderId = json(online(customerA, onlineBody("CASH", 2), key)).get("orderId").asText();
        ItemEntity current = reloadItem();
        current.setActive(false);
        itemRepository.save(current);

        MvcResult replay = online(customerA, onlineBody("CASH", 2), key);

        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(orderId, json(replay).get("orderId").asText());
    }

    // ---- failed attempts leave no key behind ----

    @Test
    void failedCreate_doesNotConsumeTheKey_soTheSameKeyCanBeRetried() throws Exception {
        String key = key();
        String tooMany = onlineBody("CASH", 1000);

        assertEquals(409, online(customerA, tooMany, key).getResponse().getStatus()); // insufficient stock
        assertEquals(0, orderEntityRepository.count());
        assertStock(100, 0);

        // e.g. the customer lowers the quantity and retries with the same key
        assertEquals(201, online(customerA, onlineBody("CASH", 3), key).getResponse().getStatus());
        assertEquals(1, orderEntityRepository.count());
        assertStock(97, 0);
    }

    // ---- response secrecy ----

    @Test
    void responses_neverExposeTheKeyOrFingerprint() throws Exception {
        String key = "secret-key-" + UUID.randomUUID();
        MvcResult created = online(customerA, onlineBody("CASH", 1), key);
        MvcResult replay = online(customerA, onlineBody("CASH", 1), key);
        String fingerprint = orderEntityRepository.findAll().get(0).getIdempotencyFingerprint();

        for (MvcResult result : List.of(created, replay)) {
            String raw = result.getResponse().getContentAsString();
            assertFalse(raw.contains(key), raw);
            assertFalse(raw.contains(fingerprint), raw);
            assertFalse(raw.toLowerCase().contains("idempotency"), raw);
        }
        assertEquals(64, fingerprint.length());
        assertEquals(key, orderEntityRepository.findAll().get(0).getIdempotencyKey());
    }

    // ---- database uniqueness ----

    @Test
    void database_rejectsTwoOrdersWithTheSameIdempotencyKey_butAllowsManyNulls() {
        orderEntityRepository.saveAndFlush(bareOrder("dup-key"));
        assertThrows(DataIntegrityViolationException.class,
                () -> orderEntityRepository.saveAndFlush(bareOrder("dup-key")));

        orderEntityRepository.saveAndFlush(bareOrder(null));
        orderEntityRepository.saveAndFlush(bareOrder(null));

        assertEquals(3, orderEntityRepository.count());
    }

    // ---- CORS: the browser must be allowed to send the header ----

    @Test
    void corsPreflight_allowsTheIdempotencyKeyHeader() throws Exception {
        MvcResult preflight = mockMvc.perform(options("/orders")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andReturn();

        assertEquals(200, preflight.getResponse().getStatus());
        assertTrue(preflight.getResponse().getHeader("Access-Control-Allow-Headers").toLowerCase().contains("idempotency-key"));
    }

    // ---- concurrency ----

    @Test
    void concurrentIdenticalRequests_createOneOrder_andMoveStockOnce() throws Exception {
        String key = key();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return online(customerA, onlineBody("CASH", 2), key).getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // the invariants that matter, whatever the interleaving: one order, one stock movement
        assertEquals(1, orderEntityRepository.count(), "statuses: " + statuses);
        assertStock(98, 0);
        assertEquals(1, statuses.stream().filter(status -> status == 201).count(), "statuses: " + statuses);
        assertTrue(statuses.stream().allMatch(status -> status == 201 || status == 200), "statuses: " + statuses);
    }

    // ---- fixtures ----

    private OrderEntity bareOrder(String idempotencyKey) {
        return OrderEntity.builder()
                .customerName("X").phoneNumber("9999999999")
                .subtotal(new BigDecimal("10.0")).tax(new BigDecimal("0.1")).grandTotal(new BigDecimal("10.1"))
                .paymentMethod(PaymentMethod.CASH).orderStatus(OrderStatus.PAID)
                .paymentDetails(PaymentDetails.builder().status(PaymentDetails.PaymentStatus.COMPLETED).build())
                .inventoryReserved(false).user(customerA)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private PaymentVerificationRequest verification(String orderId, String razorpayOrderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(razorpayKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((razorpayOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId(orderId);
        request.setRazorpayOrderId(razorpayOrderId);
        request.setRazorpayPaymentId(paymentId);
        request.setRazorpaySignature(hex.toString());
        return request;
    }
}
