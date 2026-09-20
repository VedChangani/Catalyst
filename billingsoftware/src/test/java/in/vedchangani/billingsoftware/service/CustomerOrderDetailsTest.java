package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.TestMoney;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch 7: customer order details (GET /orders/{orderId}) and the unified ONLINE + POS purchase
 * history (GET /orders/my-orders), through the real security chain and real H2 persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerOrderDetailsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;

    private UserEntity customerA;
    private UserEntity customerB;
    private UserEntity cashier;
    private UserEntity admin;
    private ItemEntity coffee;
    private String suffix;
    private int seq;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        customerA = aUser("Aaron Customer", "aaron-" + suffix + "@example.com", "ROLE_USER");
        customerB = aUser("Bella Customer", "bella-" + suffix + "@example.com", "ROLE_USER");
        cashier = aUser("Casey Cashier", "casey-" + suffix + "@example.com", "ROLE_CASHIER");
        admin = aUser("Ada Admin", "ada-" + suffix + "@example.com", "ROLE_ADMIN");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("od-cat-" + suffix).name("Order details " + suffix).build());
        coffee = itemRepository.save(ItemEntity.builder()
                .itemId("od-item-" + suffix).name("Coffee").price(BigDecimal.valueOf(100))
                .category(category).stockQuantity(100).reservedQuantity(0)
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

    private UserEntity aUser(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).password("not-used")
                .role(role).name(name).mobile(in.vedchangani.billingsoftware.TestMobiles.next()).build());
    }

    // Seeds an order directly, with a fixed orderId/createdAt (the entity's @PrePersist would
    // otherwise stamp a millisecond-based id that two quick inserts could share).
    private OrderEntity seed(UserEntity customer, UserEntity createdBy, SalesChannel channel,
                             LocalDateTime createdAt, String customerName, String phone) {
        List<OrderItemEntity> lines = new ArrayList<>();
        lines.add(OrderItemEntity.builder().itemId(coffee.getItemId()).name("Coffee").price(new BigDecimal("100.0")).quantity(3).build());
        OrderEntity order = orderEntityRepository.save(OrderEntity.builder()
                .customerName(customerName).phoneNumber(phone)
                .subtotal(new BigDecimal("300.0")).tax(new BigDecimal("3.0")).grandTotal(new BigDecimal("303.0"))
                .paymentMethod(PaymentMethod.CASH).orderStatus(OrderStatus.PAID)
                .paymentDetails(PaymentDetails.builder().status(PaymentDetails.PaymentStatus.COMPLETED).build())
                .items(lines).user(customer).createdBy(createdBy).salesChannel(channel)
                .inventoryReserved(false).build());
        order.setOrderId("ORD-T-" + suffix + "-" + (seq++));
        order.setCreatedAt(createdAt);
        return orderEntityRepository.save(order);
    }

    private OrderEntity seed(UserEntity customer, UserEntity createdBy, SalesChannel channel) {
        return seed(customer, createdBy, channel, LocalDateTime.now(), "Someone", "9999999999");
    }

    private MvcResult getAs(UserEntity actor, String role, String url, int expectedStatus) throws Exception {
        return mockMvc.perform(get(url).with(user(actor.getEmail()).roles(role)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode detail(UserEntity actor, OrderEntity order) throws Exception {
        return json(getAs(actor, "USER", "/orders/" + order.getOrderId(), 200));
    }

    private String createOnlineOrder(UserEntity customer, String paymentMethod) throws Exception {
        String body = "{\"customerName\":\"A\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"" + paymentMethod
                + "\",\"cartItems\":[{\"itemId\":\"" + coffee.getItemId() + "\",\"quantity\":2}]}";
        MvcResult result = mockMvc.perform(post("/orders").with(user(customer.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("orderId").asText();
    }

    private String createPosOrder(UserEntity staff, String staffRole, UserEntity customer, String paymentMethod) throws Exception {
        String customerPart = customer == null ? "" : "\"customerUserId\":\"" + customer.getUserId() + "\",";
        String body = "{" + customerPart + "\"paymentMethod\":\"" + paymentMethod
                + "\",\"cartItems\":[{\"itemId\":\"" + coffee.getItemId() + "\",\"quantity\":2}]}";
        MvcResult result = mockMvc.perform(post("/pos/orders").with(user(staff.getEmail()).roles(staffRole))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("orderId").asText();
    }

    // ---- customer history ----

    @Test
    void history_includesOwnOnlineAndPosOrders_newestFirst() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        OrderEntity online = seed(customerA, null, SalesChannel.ONLINE, now.minusHours(3), "A", "9999999999");
        OrderEntity pos = seed(customerA, cashier, SalesChannel.POS, now.minusHours(1), "A", "9999999999");
        OrderEntity legacy = seed(customerA, null, null, now.minusHours(5), "A", "9999999999");

        JsonNode history = json(getAs(customerA, "USER", "/orders/my-orders", 200));

        assertEquals(3, history.size());
        assertEquals(pos.getOrderId(), history.get(0).get("orderId").asText());
        assertEquals("POS", history.get(0).get("salesChannel").asText());
        assertEquals(online.getOrderId(), history.get(1).get("orderId").asText());
        assertEquals("ONLINE", history.get(1).get("salesChannel").asText());
        assertEquals(legacy.getOrderId(), history.get(2).get("orderId").asText());
        assertTrue(history.get(2).get("salesChannel") == null || history.get(2).get("salesChannel").isNull());
    }

    @Test
    void history_excludesOtherCustomersAndWalkInOrders_evenWithMatchingNameOrPhone() throws Exception {
        OrderEntity mine = seed(customerA, cashier, SalesChannel.POS);
        // other customer's orders, both channels
        seed(customerB, null, SalesChannel.ONLINE);
        seed(customerB, cashier, SalesChannel.POS);
        // walk-in POS orders that carry customer A's name and phone but no account
        seed(null, cashier, SalesChannel.POS, LocalDateTime.now(), customerA.getName(), "9876543210");
        // an order "created by" customer A's account id but belonging to nobody / someone else
        seed(customerB, customerA, SalesChannel.POS, LocalDateTime.now(), customerA.getName(), "9876543210");

        JsonNode history = json(getAs(customerA, "USER", "/orders/my-orders", 200));

        assertEquals(1, history.size());
        assertEquals(mine.getOrderId(), history.get(0).get("orderId").asText());
    }

    @Test
    void history_forAnotherUserIsIndependent() throws Exception {
        seed(customerA, null, SalesChannel.ONLINE);

        assertEquals(0, json(getAs(customerB, "USER", "/orders/my-orders", 200)).size());
    }

    // ---- customer detail: access ----

    @Test
    void detail_ownOnlineOrder_succeeds() throws Exception {
        OrderEntity order = seed(customerA, null, SalesChannel.ONLINE);

        JsonNode body = detail(customerA, order);

        assertEquals(order.getOrderId(), body.get("orderId").asText());
        assertEquals("ONLINE", body.get("salesChannel").asText());
    }

    @Test
    void detail_ownRegisteredCustomerPosOrder_succeeds_withoutStaffIdentity() throws Exception {
        OrderEntity order = seed(customerA, cashier, SalesChannel.POS);

        MvcResult result = getAs(customerA, "USER", "/orders/" + order.getOrderId(), 200);
        JsonNode body = json(result);

        assertEquals("POS", body.get("salesChannel").asText());
        assertTrue(body.get("createdBy") == null || body.get("createdBy").isNull());
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains(cashier.getUserId()));
        assertFalse(raw.contains(cashier.getEmail()));
        assertFalse(raw.contains(cashier.getName()));
    }

    @Test
    void detail_anotherUsersOnlineOrPosOrder_isDenied() throws Exception {
        OrderEntity online = seed(customerB, null, SalesChannel.ONLINE);
        OrderEntity pos = seed(customerB, cashier, SalesChannel.POS);

        getAs(customerA, "USER", "/orders/" + online.getOrderId(), 403);
        getAs(customerA, "USER", "/orders/" + pos.getOrderId(), 403);
    }

    @Test
    void detail_walkInPosOrder_isDenied_evenWhenNameAndPhoneMatchTheCaller() throws Exception {
        OrderEntity walkIn = seed(null, cashier, SalesChannel.POS, LocalDateTime.now(), customerA.getName(), "9876543210");

        getAs(customerA, "USER", "/orders/" + walkIn.getOrderId(), 403);
    }

    @Test
    void detail_nameOrPhoneMatch_doesNotGrantAnotherUsersOrder() throws Exception {
        // customer B's order carries customer A's exact name and phone.
        OrderEntity order = seed(customerB, null, SalesChannel.ONLINE, LocalDateTime.now(), customerA.getName(), "9876543210");

        getAs(customerA, "USER", "/orders/" + order.getOrderId(), 403);
    }

    @Test
    void detail_createdByDoesNotGrantCustomerAccess() throws Exception {
        // customer A's account is recorded as createdBy of an order that belongs to B.
        OrderEntity order = seed(customerB, customerA, SalesChannel.POS);

        getAs(customerA, "USER", "/orders/" + order.getOrderId(), 403);
        assertEquals(0, json(getAs(customerA, "USER", "/orders/my-orders", 200)).size());
    }

    @Test
    void detail_nonexistentOrder_is404() throws Exception {
        getAs(customerA, "USER", "/orders/ORD-DOES-NOT-EXIST", 404);
    }

    @Test
    void detail_endpointRequiresUserRoleAndAuthentication() throws Exception {
        OrderEntity order = seed(customerA, null, SalesChannel.ONLINE);

        mockMvc.perform(get("/orders/" + order.getOrderId())).andExpect(status().isUnauthorized());
        getAs(cashier, "CASHIER", "/orders/" + order.getOrderId(), 403);
        getAs(admin, "ADMIN", "/orders/" + order.getOrderId(), 403);
    }

    @Test
    void literalRoutesAreNotShadowedByTheOrderIdRoute() throws Exception {
        // /orders/latest stays ADMIN-only and /orders/my-orders stays a list.
        getAs(customerA, "USER", "/orders/latest", 403);
        getAs(admin, "ADMIN", "/orders/latest", 200);
        assertTrue(json(getAs(customerA, "USER", "/orders/my-orders", 200)).isArray());
    }

    // ---- historical snapshot ----

    @Test
    void detail_usesHistoricalSnapshot_notCurrentCatalog() throws Exception {
        String orderId = createOnlineOrder(customerA, "CASH");
        OrderEntity before = orderEntityRepository.findByOrderId(orderId).orElseThrow();

        // The catalog changes after the purchase.
        ItemEntity current = itemRepository.findByItemId(coffee.getItemId()).orElseThrow();
        current.setPrice(BigDecimal.valueOf(140));
        current.setName("Premium Coffee");
        itemRepository.save(current);

        JsonNode line = detail(customerA, before).get("items").get(0);

        assertEquals("Coffee", line.get("name").asText());
        assertEquals(100.0, line.get("price").asDouble(), 0.0001);
        assertEquals(2, line.get("quantity").asInt());
        assertEquals(200.0, line.get("lineTotal").asDouble(), 0.0001);
        // the history list is snapshot-based too
        JsonNode listed = json(getAs(customerA, "USER", "/orders/my-orders", 200)).get(0).get("items").get(0);
        assertEquals("Coffee", listed.get("name").asText());
        assertEquals(100.0, listed.get("price").asDouble(), 0.0001);
    }

    @Test
    void detail_leavesPersistedTotalsUntouched_andReportsThem() throws Exception {
        String orderId = createOnlineOrder(customerA, "CASH");
        OrderEntity before = orderEntityRepository.findByOrderId(orderId).orElseThrow();
        BigDecimal subtotal = before.getSubtotal();
        BigDecimal tax = before.getTax();
        BigDecimal grand = before.getGrandTotal();

        ItemEntity current = itemRepository.findByItemId(coffee.getItemId()).orElseThrow();
        current.setPrice(BigDecimal.valueOf(999));
        itemRepository.save(current);

        JsonNode body = detail(customerA, before);

        TestMoney.assertMoney(subtotal.toPlainString(), body.get("subtotal").decimalValue());
        TestMoney.assertMoney(tax.toPlainString(), body.get("tax").decimalValue());
        TestMoney.assertMoney(grand.toPlainString(), body.get("grandTotal").decimalValue());
        TestMoney.assertMoney("200.00", subtotal);
        OrderEntity after = orderEntityRepository.findByOrderId(orderId).orElseThrow();
        TestMoney.assertMoney(subtotal.toPlainString(), after.getSubtotal());
        TestMoney.assertMoney(tax.toPlainString(), after.getTax());
        TestMoney.assertMoney(grand.toPlainString(), after.getGrandTotal());
    }

    @Test
    void detail_exposesTheFieldsAFutureDetailsPageNeeds() throws Exception {
        OrderEntity order = seed(customerA, null, SalesChannel.ONLINE, LocalDateTime.now(), "Aaron", "9999999999");

        JsonNode body = detail(customerA, order);

        for (String field : new String[]{"orderId", "createdAt", "salesChannel", "customerName", "phoneNumber",
                "items", "subtotal", "tax", "grandTotal", "paymentMethod", "paymentStatus", "orderStatus"}) {
            assertNotNull(body.get(field), field);
            assertFalse(body.get(field).isNull(), field);
        }
        assertEquals("CASH", body.get("paymentMethod").asText());
        assertEquals("COMPLETED", body.get("paymentStatus").asText());
        assertEquals("PAID", body.get("orderStatus").asText());
        JsonNode line = body.get("items").get(0);
        assertEquals(coffee.getItemId(), line.get("itemId").asText());
        assertEquals(300.0, line.get("lineTotal").asDouble(), 0.0001);
    }

    // ---- payment security ----

    @Test
    void customerResponses_neverContainSignatureSecretsOrCredentials() throws Exception {
        OrderEntity order = seed(customerA, null, SalesChannel.ONLINE);
        order.setPaymentMethod(PaymentMethod.UPI);
        order.setPaymentDetails(PaymentDetails.builder()
                .razorpayOrderId("order_pub123").razorpayPaymentId("pay_pub123")
                .razorpaySignature("SECRET_SIGNATURE_VALUE").status(PaymentDetails.PaymentStatus.COMPLETED).build());
        orderEntityRepository.save(order);

        String detailJson = getAs(customerA, "USER", "/orders/" + order.getOrderId(), 200).getResponse().getContentAsString();
        String listJson = getAs(customerA, "USER", "/orders/my-orders", 200).getResponse().getContentAsString();

        for (String raw : new String[]{detailJson, listJson}) {
            String lower = raw.toLowerCase();
            assertFalse(lower.contains("razorpaysignature"), raw);
            assertFalse(raw.contains("SECRET_SIGNATURE_VALUE"), raw);
            assertFalse(lower.contains("password"), raw);
            assertFalse(lower.contains("not-used"), raw);
            assertFalse(lower.contains("jwt"), raw);
            assertFalse(lower.contains("secret"), raw.replace("SECRET_SIGNATURE_VALUE", ""));
            assertTrue(raw.contains("order_pub123"));
            assertTrue(raw.contains("pay_pub123"));
        }
    }

    // ---- POS payment lifecycle is unchanged ----

    @Test
    void posCustomer_canSeeTheirPosOrderButCannotOperateItsPaymentLifecycle() throws Exception {
        String orderId = createPosOrder(cashier, "CASHIER", customerA, "UPI");

        // visible in history and detail
        JsonNode history = json(getAs(customerA, "USER", "/orders/my-orders", 200));
        assertEquals(1, history.size());
        assertEquals(orderId, history.get(0).get("orderId").asText());
        getAs(customerA, "USER", "/orders/" + orderId, 200);

        // but no lifecycle control
        mockMvc.perform(post("/orders/" + orderId + "/cancel").with(user(customerA.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/orders/" + orderId + "/fail-payment").with(user(customerA.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/payments/create-order").with(user(customerA.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"currency\":\"INR\"}"))
                .andExpect(status().isForbidden());
        assertEquals(OrderStatus.PENDING_PAYMENT, orderEntityRepository.findByOrderId(orderId).orElseThrow().getOrderStatus());

        // the creating cashier still can
        mockMvc.perform(post("/orders/" + orderId + "/cancel").with(user(cashier.getEmail()).roles("CASHIER")))
                .andExpect(status().isOk());
    }

    @Test
    void sameCustomer_seesOnlineAndPosPurchasesTogether_viaRealCreationFlows() throws Exception {
        String online = createOnlineOrder(customerA, "CASH");
        String pos = createPosOrder(cashier, "CASHIER", customerA, "CASH");
        createPosOrder(cashier, "CASHIER", null, "CASH"); // walk-in: must not appear

        JsonNode history = json(getAs(customerA, "USER", "/orders/my-orders", 200));

        assertEquals(2, history.size());
        List<String> ids = List.of(history.get(0).get("orderId").asText(), history.get(1).get("orderId").asText());
        assertTrue(ids.contains(online));
        assertTrue(ids.contains(pos));
        // each detail is reachable
        getAs(customerA, "USER", "/orders/" + online, 200);
        getAs(customerA, "USER", "/orders/" + pos, 200);
    }
}
