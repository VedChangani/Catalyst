package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.TestMoney;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.AppUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end Batch 6 behavior (roles, sales channel, createdBy, POS customer association) against
 * the H2 "test" datasource: real SecurityConfig filter chain, real controllers/services/repositories.
 *
 * Deliberately NOT @Transactional, so the service's own transactions commit or roll back for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetailChannelIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderService orderService;
    @Autowired private UserService userService;
    @Autowired private RazorpayService razorpayService;
    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private UserRepository userRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;

    private UserEntity customerA;
    private UserEntity customerB;
    private UserEntity cashier;
    private UserEntity otherCashier;
    private UserEntity admin;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        customerA = aUser("Aaron Customer", "aaron-" + s + "@example.com", "ROLE_USER");
        customerB = aUser("Bella Customer", "bella-" + s + "@example.com", "ROLE_USER");
        cashier = aUser("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        otherCashier = aUser("Drew Cashier", "drew-" + s + "@example.com", "ROLE_CASHIER");
        admin = aUser("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("rc-cat-" + s).name("Retail channel " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("rc-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(100).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());
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

    private void authenticateAs(UserEntity user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
    }

    // The order-list reads rely on the web layer's open-in-view session for lazy collections;
    // outside a web request, provide that session with a transaction.
    private <T> T inSession(java.util.function.Supplier<T> call) {
        return transactionTemplate.execute(status -> call.get());
    }

    private String cart() {
        return "[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":2}]";
    }

    private JsonNode postJson(String url, UserEntity actor, String role, String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .with(user(actor.getEmail()).roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : objectMapper.readTree(content);
    }

    private OrderEntity storedOrder(JsonNode response) {
        return orderEntityRepository.findByOrderId(response.get("orderId").asText()).orElseThrow();
    }

    private ItemEntity reloadItem() {
        return itemRepository.findByItemId(item.getItemId()).orElseThrow();
    }

    // ---- roles ----

    @Test
    void cashierRoleIsPersistedAsIsAndRecognizedBySpringSecurity() {
        UserDetails details = appUserDetailsService.loadUserByUsername(cashier.getEmail());
        assertEquals("ROLE_CASHIER", details.getAuthorities().iterator().next().getAuthority());
        assertEquals("ROLE_CASHIER", userService.getUserRole(cashier.getEmail()));
        // existing roles are untouched
        assertEquals("ROLE_USER", userService.getUserRole(customerA.getEmail()));
        assertEquals("ROLE_ADMIN", userService.getUserRole(admin.getEmail()));
    }

    // ---- ONLINE ----

    @Test
    void onlineOrder_isOnlineOwnedByCaller_withNoCreator_evenWithHostileBodyFields() throws Exception {
        // salesChannel / createdBy / userId in the body must all be ignored.
        String body = "{\"customerName\":\"" + customerB.getName() + "\",\"phoneNumber\":\"9999999999\","
                + "\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + ","
                + "\"salesChannel\":\"POS\",\"createdBy\":\"" + cashier.getUserId() + "\","
                + "\"userId\":\"" + customerB.getUserId() + "\",\"customerUserId\":\"" + customerB.getUserId() + "\"}";

        JsonNode response = postJson("/orders", customerA, "USER", body, 201);

        OrderEntity order = storedOrder(response);
        assertEquals(SalesChannel.ONLINE, order.getSalesChannel());
        assertEquals(customerA.getId(), order.getUser().getId());
        assertNull(order.getCreatedBy());
        assertEquals("ONLINE", response.get("salesChannel").asText());
        assertTrue(response.get("createdBy") == null || response.get("createdBy").isNull());
    }

    @Test
    void onlineEndpointIsUserOnly_adminAndCashierMustUsePos() throws Exception {
        String body = "{\"customerName\":\"X\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";
        assertEquals(SalesChannel.ONLINE, storedOrder(postJson("/orders", customerA, "USER", body, 201)).getSalesChannel());
        postJson("/orders", admin, "ADMIN", body, 403);
        postJson("/orders", cashier, "CASHIER", body, 403);
        assertEquals(1, orderEntityRepository.count());
    }

    // ---- POS ----

    @Test
    void walkInPosOrder_byCashier_hasNoUserAndCreatedByCashier() throws Exception {
        String body = "{\"customerName\":\"Walk In\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        JsonNode response = postJson("/pos/orders", cashier, "CASHIER", body, 201);

        OrderEntity order = storedOrder(response);
        assertEquals(SalesChannel.POS, order.getSalesChannel());
        assertNull(order.getUser());
        assertEquals(cashier.getId(), order.getCreatedBy().getId());
        assertEquals("POS", response.get("salesChannel").asText());
        assertEquals(cashier.getUserId(), response.get("createdBy").get("userId").asText());
        assertEquals(cashier.getName(), response.get("createdBy").get("name").asText());
        // never leaks credentials/email of the creator
        assertNull(response.get("createdBy").get("email"));
        assertNull(response.get("createdBy").get("password"));
        // shared billing logic: authoritative price/tax/total and CASH commits stock
        TestMoney.assertMoney("20.0", order.getSubtotal());
        TestMoney.assertMoney("0.2", order.getTax());
        TestMoney.assertMoney("20.2", order.getGrandTotal());
        assertEquals(OrderStatus.PAID, order.getOrderStatus());
        assertEquals(98, reloadItem().getStockQuantity());
        assertEquals(0, reloadItem().getReservedQuantity());
    }

    @Test
    void posOrder_byAdmin_isForbidden_andNothingIsCreatedOrReserved() throws Exception {
        String body = "{\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        postJson("/pos/orders", admin, "ADMIN", body, 403);

        assertEquals(0, orderEntityRepository.count());
        assertEquals(100, reloadItem().getStockQuantity());
        assertEquals(0, reloadItem().getReservedQuantity());
    }

    @Test
    void posOrder_byAdmin_isRefusedByTheServiceToo_notOnlyByTheUrlRule() {
        authenticateAs(admin);
        PosOrderRequest request = PosOrderRequest.builder().paymentMethod("CASH")
                .cartItems(List.of(new OrderRequest.OrderItemRequest(item.getItemId(), 1))).build();

        assertThrows(AccessDeniedException.class, () -> orderService.createPosOrder(request));
        assertEquals(0, orderEntityRepository.count());
    }

    @Test
    void posCustomerLookup_isCashierOnly() throws Exception {
        mockMvc.perform(get("/pos/customers").param("search", "ab").with(user(cashier.getEmail()).roles("CASHIER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/pos/customers").param("search", "ab").with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/pos/customers").param("search", "ab").with(user(customerA.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void mySales_listsOnlyTheCallingCashiersOwnPosOrders() throws Exception {
        String body = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";
        String mine = postJson("/pos/orders", cashier, "CASHIER", body, 201).get("orderId").asText();
        String theirs = postJson("/pos/orders", otherCashier, "CASHIER", body, 201).get("orderId").asText();
        String online = postJson("/orders", customerA, "USER",
                "{\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}", 201).get("orderId").asText();

        MvcResult result = mockMvc.perform(get("/pos/sales").with(user(cashier.getEmail()).roles("CASHIER")))
                .andExpect(status().isOk()).andReturn();
        JsonNode sales = objectMapper.readTree(result.getResponse().getContentAsString());

        assertEquals(1, sales.size());
        assertEquals(mine, sales.get(0).get("orderId").asText());
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains(theirs));
        assertFalse(raw.contains(online));
        // an admin, a customer (even the one linked to the sale) and anonymous callers are refused
        mockMvc.perform(get("/pos/sales").with(user(admin.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/pos/sales").with(user(customerA.getEmail()).roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/pos/sales")).andExpect(status().isUnauthorized());
    }

    @Test
    void admin_stillSeesPosOrders_includingHistoricalAdminCreatedOnes_inAllOrders() throws Exception {
        String body = "{\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";
        String cashierSale = postJson("/pos/orders", cashier, "CASHIER", body, 201).get("orderId").asText();
        // a POS sale an admin entered back when admins were still allowed to
        String historical = postJson("/pos/orders", cashier, "CASHIER", body, 201).get("orderId").asText();
        OrderEntity legacy = orderEntityRepository.findByOrderId(historical).orElseThrow();
        legacy.setCreatedBy(admin);
        orderEntityRepository.save(legacy);

        MvcResult result = mockMvc.perform(get("/admin/orders").with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        String raw = result.getResponse().getContentAsString();

        assertTrue(raw.contains(cashierSale));
        assertTrue(raw.contains(historical));
    }

    @Test
    void posOrder_forRegisteredCustomer_associatesResolvedEntity_andDefaultsNameFromAccount() throws Exception {
        String body = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        JsonNode response = postJson("/pos/orders", cashier, "CASHIER", body, 201);

        OrderEntity order = storedOrder(response);
        assertEquals(customerA.getId(), order.getUser().getId());
        assertEquals(cashier.getId(), order.getCreatedBy().getId());
        assertEquals(SalesChannel.POS, order.getSalesChannel());
        assertEquals(customerA.getName(), order.getCustomerName());
    }

    @Test
    void posOrder_forRegisteredCustomer_isReportedToTheCashierAsThatCustomer_notWalkIn() throws Exception {
        String body = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        JsonNode created = postJson("/pos/orders", cashier, "CASHIER", body, 201);

        // creation response, snapshot and the DB row all identify the selected customer
        assertEquals(customerA.getUserId(), created.get("customer").get("userId").asText());
        assertEquals(customerA.getName(), created.get("customerName").asText());
        assertEquals(customerA.getMobile(), created.get("phoneNumber").asText());
        assertNull(created.get("customer").get("password"));
        OrderEntity order = storedOrder(created);
        assertEquals(customerA.getName(), order.getCustomerName());
        assertEquals(customerA.getMobile(), order.getPhoneNumber());

        // My Sales (what the cashier screen renders) carries the same identity
        authenticateAs(cashier);
        OrderResponse sale = inSession(orderService::getMySales).stream()
                .filter(o -> o.getOrderId().equals(order.getOrderId())).findFirst().orElseThrow();
        assertNotNull(sale.getCustomer());
        assertEquals(customerA.getUserId(), sale.getCustomer().getUserId());
        assertEquals(customerA.getName(), sale.getCustomerName());
    }

    @Test
    void posOrder_typedBillingDetails_areKeptAsSnapshot_evenForARegisteredCustomer() throws Exception {
        String body = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"customerName\":\"Typed Name\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        OrderEntity order = storedOrder(postJson("/pos/orders", cashier, "CASHIER", body, 201));

        assertEquals(customerA.getId(), order.getUser().getId());
        assertEquals("Typed Name", order.getCustomerName());
        assertEquals("9999999999", order.getPhoneNumber());
    }

    @Test
    void walkInPosOrder_isReportedWithNoCustomer() throws Exception {
        String body = "{\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        JsonNode created = postJson("/pos/orders", cashier, "CASHIER", body, 201);

        assertNull(storedOrder(created).getUser());
        assertTrue(created.get("customer") == null || created.get("customer").isNull());
    }

    // The two POS customer modes must stay distinguishable in the SAME My Sales list: a sale made
    // for an explicitly selected customer identifies that customer, a genuine walk-in carries no
    // customer at all. Neither mode may collapse into the other.
    @Test
    void mySales_distinguishesARegisteredSaleFromAGenuineWalkIn() throws Exception {
        String registered = postJson("/pos/orders", cashier, "CASHIER",
                "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}",
                201).get("orderId").asText();
        String walkIn = postJson("/pos/orders", cashier, "CASHIER",
                "{\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}", 201).get("orderId").asText();

        // the persisted rows are what My Sales must reflect
        assertEquals(customerA.getId(), orderEntityRepository.findByOrderId(registered).orElseThrow().getUser().getId());
        assertNull(orderEntityRepository.findByOrderId(walkIn).orElseThrow().getUser());

        authenticateAs(cashier);
        List<OrderResponse> sales = inSession(orderService::getMySales);
        OrderResponse registeredSale = sales.stream()
                .filter(o -> o.getOrderId().equals(registered)).findFirst().orElseThrow();
        OrderResponse walkInSale = sales.stream()
                .filter(o -> o.getOrderId().equals(walkIn)).findFirst().orElseThrow();

        assertNotNull(registeredSale.getCustomer());
        assertEquals(customerA.getUserId(), registeredSale.getCustomer().getUserId());
        assertEquals(customerA.getName(), registeredSale.getCustomer().getName());
        assertNull(walkInSale.getCustomer());
        assertEquals(cashier.getUserId(), walkInSale.getCreatedBy().getUserId());
        assertEquals(SalesChannel.POS, walkInSale.getSalesChannel());
    }

    @Test
    void registeredPosOrder_isVisibleToItsCustomer_butNotToAnotherCustomerOrCashier() throws Exception {
        String body = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";
        String orderId = postJson("/pos/orders", cashier, "CASHIER", body, 201).get("orderId").asText();

        authenticateAs(customerA);
        assertEquals(orderId, inSession(() -> orderService.getMyOrder(orderId)).getOrderId());
        authenticateAs(customerB);
        assertThrows(AccessDeniedException.class, () -> inSession(() -> orderService.getMyOrder(orderId)));
        authenticateAs(otherCashier);
        assertThrows(AccessDeniedException.class, () -> inSession(() -> orderService.getMyOrder(orderId)));
    }

    @Test
    void posOrder_withUnknownCustomer_failsSafely_andReservesNothing() throws Exception {
        String body = "{\"customerUserId\":\"does-not-exist\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        postJson("/pos/orders", cashier, "CASHIER", body, 404);

        assertEquals(0, orderEntityRepository.count());
        assertEquals(100, reloadItem().getStockQuantity());
        assertEquals(0, reloadItem().getReservedQuantity());
    }

    @Test
    void posOrder_withBlankCustomerId_isRejected() throws Exception {
        String body = "{\"customerUserId\":\"  \",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        postJson("/pos/orders", cashier, "CASHIER", body, 400);

        assertEquals(0, orderEntityRepository.count());
    }

    @Test
    void posOrder_cannotAssociateStaffAccountAsCustomer() throws Exception {
        // A staff account's userId is not a valid *customer* selection; reported as not found.
        String body = "{\"customerUserId\":\"" + otherCashier.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        postJson("/pos/orders", cashier, "CASHIER", body, 404);

        assertEquals(0, orderEntityRepository.count());
    }

    @Test
    void posOrder_clientCannotChooseCreatorOrChannel() throws Exception {
        String body = "{\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + ","
                + "\"createdBy\":\"" + otherCashier.getUserId() + "\",\"salesChannel\":\"ONLINE\"}";

        OrderEntity order = storedOrder(postJson("/pos/orders", cashier, "CASHIER", body, 201));

        assertEquals(cashier.getId(), order.getCreatedBy().getId());
        assertEquals(SalesChannel.POS, order.getSalesChannel());
    }

    @Test
    void posOrder_matchingNameOrPhone_neverAssociatesAnAccount() throws Exception {
        // Exactly the registered customer's name, and a phone-like value: still a walk-in.
        String body = "{\"customerName\":\"" + customerA.getName() + "\",\"phoneNumber\":\"9876543210\","
                + "\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        OrderEntity order = storedOrder(postJson("/pos/orders", cashier, "CASHIER", body, 201));

        assertNull(order.getUser());
        assertEquals(customerA.getName(), order.getCustomerName());
        assertEquals(0, orderEntityRepository.findByUser_IdOrderByCreatedAtDesc(customerA.getId()).size());
    }

    // ---- service-level defence in depth ----

    @Test
    void createPosOrder_service_rejectsAUserRoleAccount() {
        authenticateAs(customerA);
        PosOrderRequest request = PosOrderRequest.builder().paymentMethod("CASH")
                .cartItems(List.of(new OrderRequest.OrderItemRequest(item.getItemId(), 1))).build();

        assertThrows(AccessDeniedException.class, () -> orderService.createPosOrder(request));
        assertEquals(0, orderEntityRepository.count());
    }

    @Test
    void createPosOrder_service_unknownCustomerIsNotFound() {
        authenticateAs(cashier);
        PosOrderRequest request = PosOrderRequest.builder().customerUserId("nope").paymentMethod("CASH")
                .cartItems(List.of(new OrderRequest.OrderItemRequest(item.getItemId(), 1))).build();

        assertThrows(ResourceNotFoundException.class, () -> orderService.createPosOrder(request));
    }

    // ---- unified customer history / visibility ----

    @Test
    void sameCustomerAccount_hasBothOnlineAndPosOrders_withoutDuplicateAccounts() throws Exception {
        long usersBefore = userRepository.count();
        String online = "{\"customerName\":\"A\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";
        String pos = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";

        postJson("/orders", customerA, "USER", online, 201);
        postJson("/pos/orders", cashier, "CASHIER", pos, 201);

        assertEquals(usersBefore, userRepository.count());
        authenticateAs(customerA);
        List<OrderResponse> history = inSession(orderService::getMyOrders);
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(o -> o.getSalesChannel() == SalesChannel.ONLINE));
        assertTrue(history.stream().anyMatch(o -> o.getSalesChannel() == SalesChannel.POS));
        // the customer's own history never names the staff member
        assertTrue(history.stream().allMatch(o -> o.getCreatedBy() == null));
    }

    @Test
    void userCannotSeeAnotherUsersPosOrder() throws Exception {
        String pos = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";
        postJson("/pos/orders", cashier, "CASHIER", pos, 201);

        authenticateAs(customerB);
        assertTrue(inSession(orderService::getMyOrders).isEmpty());
    }

    @Test
    void adminOrderList_showsChannelAndCreator() throws Exception {
        String pos = "{\"paymentMethod\":\"CASH\",\"cartItems\":" + cart() + "}";
        postJson("/pos/orders", cashier, "CASHIER", pos, 201);

        List<OrderResponse> all = inSession(orderService::getLatestOrders);

        assertEquals(1, all.size());
        assertEquals(SalesChannel.POS, all.get(0).getSalesChannel());
        assertEquals(cashier.getUserId(), all.get(0).getCreatedBy().getUserId());
    }

    @Test
    void legacyOrderWithNullChannelAndCreator_remainsReadable() {
        orderEntityRepository.save(OrderEntity.builder()
                .customerName("Legacy").phoneNumber("7777777777")
                .subtotal(new BigDecimal("10.0")).tax(new BigDecimal("0.1")).grandTotal(new BigDecimal("10.1"))
                .paymentMethod(PaymentMethod.CASH).orderStatus(OrderStatus.PAID)
                .user(customerA).build());

        authenticateAs(customerA);
        List<OrderResponse> mine = inSession(orderService::getMyOrders);
        assertEquals(1, mine.size());
        assertNull(mine.get(0).getSalesChannel());
        assertNull(mine.get(0).getCreatedBy());

        List<OrderResponse> all = inSession(orderService::getLatestOrders);
        assertEquals(1, all.size());
        assertNull(all.get(0).getSalesChannel());
        assertNull(all.get(0).getCreatedBy());
    }

    // ---- POS payment lifecycle reuses the existing architecture ----

    @Test
    void posUpiOrder_reservesStock_andOnlyItsCreatorMayCancelIt() throws Exception {
        String body = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"UPI\",\"cartItems\":" + cart() + "}";
        JsonNode response = postJson("/pos/orders", cashier, "CASHIER", body, 201);
        String orderId = response.get("orderId").asText();
        assertEquals("PENDING_PAYMENT", response.get("orderStatus").asText());
        assertEquals(2, reloadItem().getReservedQuantity());

        // another cashier, an unrelated customer, and even the order's own associated customer
        // are all refused - and nothing is released
        authenticateAs(otherCashier);
        assertThrows(AccessDeniedException.class, () -> orderService.cancelOrder(orderId));
        authenticateAs(customerB);
        assertThrows(AccessDeniedException.class, () -> orderService.cancelOrder(orderId));
        authenticateAs(customerA);
        assertThrows(AccessDeniedException.class, () -> orderService.cancelOrder(orderId));
        assertThrows(AccessDeniedException.class, () -> orderService.failPayment(orderId));
        assertEquals(2, reloadItem().getReservedQuantity());

        // the creating cashier can release it
        authenticateAs(cashier);
        assertEquals(OrderStatus.CANCELLED, orderService.cancelOrder(orderId).getOrderStatus());
        assertEquals(0, reloadItem().getReservedQuantity());
        assertEquals(100, reloadItem().getStockQuantity());
    }

    @Test
    void walkInPosUpiOrder_canBeFailedByItsCreator_butNotByAUser() throws Exception {
        String body = "{\"paymentMethod\":\"UPI\",\"cartItems\":" + cart() + "}";
        String orderId = postJson("/pos/orders", cashier, "CASHIER", body, 201).get("orderId").asText();

        authenticateAs(customerA);
        assertThrows(AccessDeniedException.class, () -> orderService.failPayment(orderId));

        authenticateAs(cashier);
        assertEquals(OrderStatus.PAYMENT_FAILED, orderService.failPayment(orderId).getOrderStatus());
        assertEquals(0, reloadItem().getReservedQuantity());
    }

    @Test
    void adminCreatedPosOrder_isManagedByThatAdminOnly() throws Exception {
        String body = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"UPI\",\"cartItems\":" + cart() + "}";
        // historical order: entered by an admin before admins lost POS creation
        String orderId = postJson("/pos/orders", cashier, "CASHIER", body, 201).get("orderId").asText();
        OrderEntity legacy = orderEntityRepository.findByOrderId(orderId).orElseThrow();
        legacy.setCreatedBy(admin);
        orderEntityRepository.save(legacy);

        authenticateAs(customerA);
        assertThrows(AccessDeniedException.class, () -> orderService.cancelOrder(orderId));
        authenticateAs(cashier);
        assertThrows(AccessDeniedException.class, () -> orderService.cancelOrder(orderId));
        authenticateAs(admin);
        assertEquals(OrderStatus.CANCELLED, orderService.cancelOrder(orderId).getOrderStatus());
    }

    @Test
    void onlineCustomer_managesOwnPaymentLifecycle() throws Exception {
        String body = "{\"customerName\":\"A\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"UPI\",\"cartItems\":" + cart() + "}";
        String orderId = postJson("/orders", customerA, "USER", body, 201).get("orderId").asText();

        authenticateAs(customerB);
        assertThrows(AccessDeniedException.class, () -> orderService.cancelOrder(orderId));
        authenticateAs(customerA);
        assertEquals(OrderStatus.CANCELLED, orderService.cancelOrder(orderId).getOrderStatus());
        assertEquals(0, reloadItem().getReservedQuantity());
    }

    @Test
    void razorpayOrderCreation_followsTheSameOwnershipRule() throws Exception {
        String pos = "{\"customerUserId\":\"" + customerA.getUserId() + "\",\"paymentMethod\":\"UPI\",\"cartItems\":" + cart() + "}";
        String orderId = postJson("/pos/orders", cashier, "CASHIER", pos, 201).get("orderId").asText();

        // Ownership is checked before any Razorpay call is made.
        authenticateAs(customerA);
        assertThrows(AccessDeniedException.class, () -> razorpayService.createOrder(orderId, "INR"));
        authenticateAs(otherCashier);
        assertThrows(AccessDeniedException.class, () -> razorpayService.createOrder(orderId, "INR"));
    }

    @Test
    void orderResponses_neverContainTheRazorpaySignature() throws Exception {
        // Give a UPI order a fully populated persisted PaymentDetails, signature included.
        String body = "{\"customerName\":\"A\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"UPI\",\"cartItems\":" + cart() + "}";
        JsonNode created = postJson("/orders", customerA, "USER", body, 201);
        OrderEntity order = storedOrder(created);
        order.getPaymentDetails().setRazorpayOrderId("order_test123");
        order.getPaymentDetails().setRazorpayPaymentId("pay_test123");
        order.getPaymentDetails().setRazorpaySignature("SECRET_SIGNATURE_VALUE");
        orderEntityRepository.save(order);

        MvcResult mine = mockMvc.perform(get("/orders/my-orders").with(user(customerA.getEmail()).roles("USER")))
                .andExpect(status().isOk()).andReturn();
        MvcResult latest = mockMvc.perform(get("/orders/latest").with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        MvcResult cancelled = mockMvc.perform(post("/orders/" + order.getOrderId() + "/cancel")
                        .with(user(customerA.getEmail()).roles("USER")))
                .andExpect(status().isOk()).andReturn();

        for (MvcResult result : List.of(mine, latest, cancelled)) {
            String json = result.getResponse().getContentAsString();
            assertFalse(json.contains("razorpaySignature"), json);
            assertFalse(json.contains("SECRET_SIGNATURE_VALUE"), json);
            assertTrue(json.contains("order_test123"));
            assertTrue(json.contains("pay_test123"));
        }
        assertFalse(created.toString().contains("razorpaySignature"));
    }

    @Test
    void createdByDoesNotGrantAccessToOnlineOrders() throws Exception {
        String body = "{\"customerName\":\"A\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"UPI\",\"cartItems\":" + cart() + "}";
        String orderId = postJson("/orders", customerA, "USER", body, 201).get("orderId").asText();

        authenticateAs(cashier);
        assertThrows(AccessDeniedException.class, () -> orderService.cancelOrder(orderId));
        assertEquals(2, reloadItem().getReservedQuantity());
    }

    // ---- customer lookup ----

    @Test
    void customerLookup_returnsOnlyRegisteredCustomers_withMinimalFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/pos/customers").param("search", "customer")
                        .with(user(cashier.getEmail()).roles("CASHIER")))
                .andExpect(status().isOk()).andReturn();

        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, list.size());
        for (JsonNode c : list) {
            assertNotNull(c.get("userId"));
            assertNotNull(c.get("name"));
            assertNotNull(c.get("email"));
            assertNull(c.get("password"));
            assertNull(c.get("role"));
            assertEquals(3, c.size());
        }
        assertFalse(result.getResponse().getContentAsString().contains("Cashier"));
        assertFalse(result.getResponse().getContentAsString().contains("Admin"));
    }

    @Test
    void customerLookup_requiresMinimumSearchLength_andTreatsWildcardsLiterally() throws Exception {
        mockMvc.perform(get("/pos/customers").param("search", "a")
                        .with(user(cashier.getEmail()).roles("CASHIER")))
                .andExpect(status().isBadRequest());

        MvcResult result = mockMvc.perform(get("/pos/customers").param("search", "%%")
                        .with(user(cashier.getEmail()).roles("CASHIER")))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, objectMapper.readTree(result.getResponse().getContentAsString()).size());
    }
}
