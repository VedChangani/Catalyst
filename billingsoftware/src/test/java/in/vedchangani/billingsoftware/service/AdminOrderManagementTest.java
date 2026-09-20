package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.TestMoney;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch 8: GET /admin/orders (filter / search / sort / paginate) through the real security chain
 * and real H2 persistence. Fixed dataset (created dates are fixed so date filters are exact):
 *
 *   #  channel  customer  createdBy  name             phone       total  status           method  pay-status  createdAt
 *   1  ONLINE   A         -          Aaron Customer   9000000001   100   PAID             CASH    COMPLETED   2026-01-10 10:00
 *   2  ONLINE   B         -          Bella Customer   9000000002   250   PENDING_PAYMENT  UPI     PENDING     2026-01-12 12:00
 *   3  POS      A         cashier    Aaron Customer   9000000001   500   PAID             UPI     COMPLETED   2026-01-15 23:59
 *   4  POS      walk-in   cashier    Walk Ina         9000000003    50   PAID             CASH    COMPLETED   2026-01-20 00:00
 *   5  POS      walk-in   admin      Walker           9000000004  1000   CANCELLED        UPI     FAILED      2026-01-25 09:00
 *   6  ONLINE   B         -          Bella Customer   9000000002    75   PAYMENT_FAILED   UPI     FAILED      2026-02-01 09:00
 *   7  legacy   A         -          Legacy Person    9000000005    10   PAID             CASH    COMPLETED   2025-12-31 09:00
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOrderManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;

    private UserEntity customerA;
    private UserEntity customerB;
    private UserEntity cashier;
    private UserEntity admin;
    private String s;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        customerA = aUser("Aaron Customer", "aaron-" + s + "@example.com", "ROLE_USER");
        customerB = aUser("Bella Customer", "bella-" + s + "@example.com", "ROLE_USER");
        cashier = aUser("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        admin = aUser("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");

        seed(1, customerA, null, SalesChannel.ONLINE, "Aaron Customer", "9000000001", 100, OrderStatus.PAID,
                PaymentMethod.CASH, PaymentDetails.PaymentStatus.COMPLETED, LocalDateTime.of(2026, 1, 10, 10, 0));
        seed(2, customerB, null, SalesChannel.ONLINE, "Bella Customer", "9000000002", 250, OrderStatus.PENDING_PAYMENT,
                PaymentMethod.UPI, PaymentDetails.PaymentStatus.PENDING, LocalDateTime.of(2026, 1, 12, 12, 0));
        OrderEntity three = seed(3, customerA, cashier, SalesChannel.POS, "Aaron Customer", "9000000001", 500, OrderStatus.PAID,
                PaymentMethod.UPI, PaymentDetails.PaymentStatus.COMPLETED, LocalDateTime.of(2026, 1, 15, 23, 59));
        three.getPaymentDetails().setRazorpayOrderId("order_pub");
        three.getPaymentDetails().setRazorpayPaymentId("pay_pub");
        three.getPaymentDetails().setRazorpaySignature("SECRET_SIG_VALUE");
        orderEntityRepository.save(three);
        seed(4, null, cashier, SalesChannel.POS, "Walk Ina", "9000000003", 50, OrderStatus.PAID,
                PaymentMethod.CASH, PaymentDetails.PaymentStatus.COMPLETED, LocalDateTime.of(2026, 1, 20, 0, 0));
        seed(5, null, admin, SalesChannel.POS, "Walker", "9000000004", 1000, OrderStatus.CANCELLED,
                PaymentMethod.UPI, PaymentDetails.PaymentStatus.FAILED, LocalDateTime.of(2026, 1, 25, 9, 0));
        seed(6, customerB, null, SalesChannel.ONLINE, "Bella Customer", "9000000002", 75, OrderStatus.PAYMENT_FAILED,
                PaymentMethod.UPI, PaymentDetails.PaymentStatus.FAILED, LocalDateTime.of(2026, 2, 1, 9, 0));
        seed(7, customerA, null, null, "Legacy Person", "9000000005", 10, OrderStatus.PAID,
                PaymentMethod.CASH, PaymentDetails.PaymentStatus.COMPLETED, LocalDateTime.of(2025, 12, 31, 9, 0));
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity aUser(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).password("not-used")
                .role(role).name(name).mobile(in.vedchangani.billingsoftware.TestMobiles.next()).build());
    }

    private OrderEntity seed(int n, UserEntity customer, UserEntity createdBy, SalesChannel channel, String name,
                             String phone, double grandTotal, OrderStatus orderStatus, PaymentMethod method,
                             PaymentDetails.PaymentStatus payStatus, LocalDateTime createdAt) {
        List<OrderItemEntity> lines = new ArrayList<>();
        lines.add(OrderItemEntity.builder().itemId("i-" + n).name("Coffee").price(BigDecimal.valueOf(grandTotal)).quantity(1).build());
        OrderEntity order = orderEntityRepository.save(OrderEntity.builder()
                .customerName(name).phoneNumber(phone)
                .subtotal(BigDecimal.valueOf(grandTotal - 1)).tax(new BigDecimal("1.0")).grandTotal(BigDecimal.valueOf(grandTotal))
                .paymentMethod(method).orderStatus(orderStatus)
                .paymentDetails(PaymentDetails.builder().status(payStatus).build())
                .items(lines).user(customer).createdBy(createdBy).salesChannel(channel)
                .inventoryReserved(false).build());
        order.setOrderId(id(n));
        order.setCreatedAt(createdAt);
        return orderEntityRepository.save(order);
    }

    private String id(int n) {
        return "ORD-M-" + s + "-" + n;
    }

    private MvcResult call(String queryString, int expectedStatus) throws Exception {
        return mockMvc.perform(get("/admin/orders" + (queryString.isEmpty() ? "" : "?" + queryString))
                        .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode ok(String queryString) throws Exception {
        return objectMapper.readTree(call(queryString, 200).getResponse().getContentAsString());
    }

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(o -> ids.add(o.get("orderId").asText()));
        return ids;
    }

    private void assertIds(JsonNode page, int... expected) {
        List<String> want = new ArrayList<>();
        Arrays.stream(expected).forEach(n -> want.add(id(n)));
        List<String> got = ids(page);
        assertEquals(want.size(), got.size(), "got " + got);
        assertTrue(got.containsAll(want), "got " + got + " want " + want);
    }

    private JsonNode row(JsonNode page, int n) {
        for (JsonNode o : page.get("content")) {
            if (o.get("orderId").asText().equals(id(n))) {
                return o;
            }
        }
        return fail("order " + n + " not in page");
    }

    // ---- authorization ----

    @Test
    void admin_isAllowed() throws Exception {
        assertEquals(7, ok("").get("totalElements").asInt());
    }

    @Test
    void cashierAndUser_areForbidden_andAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/orders").with(user(cashier.getEmail()).roles("CASHIER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/orders").with(user(customerA.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/orders")).andExpect(status().isUnauthorized());
    }

    // ---- pagination & sorting ----

    @Test
    void defaultPage_isFirstPageOfTwentyNewestFirst_withMetadata() throws Exception {
        JsonNode page = ok("");

        assertEquals(0, page.get("page").asInt());
        assertEquals(20, page.get("size").asInt());
        assertEquals(7, page.get("totalElements").asInt());
        assertEquals(1, page.get("totalPages").asInt());
        assertTrue(page.get("first").asBoolean());
        assertTrue(page.get("last").asBoolean());
        assertEquals(List.of(id(6), id(5), id(4), id(3), id(2), id(1), id(7)), ids(page));
    }

    @Test
    void customPageAndSize_slicesTheResult_inTheDatabase() throws Exception {
        JsonNode first = ok("size=3&page=0");
        JsonNode second = ok("size=3&page=1");
        JsonNode third = ok("size=3&page=2");

        assertEquals(List.of(id(6), id(5), id(4)), ids(first));
        assertEquals(List.of(id(3), id(2), id(1)), ids(second));
        assertEquals(List.of(id(7)), ids(third));
        assertEquals(3, first.get("totalPages").asInt());
        assertEquals(7, first.get("totalElements").asInt());
        assertTrue(first.get("first").asBoolean());
        assertFalse(first.get("last").asBoolean());
        assertFalse(second.get("first").asBoolean());
        assertTrue(third.get("last").asBoolean());
    }

    @Test
    void pageBeyondTheEnd_isAnEmptyPage_not404() throws Exception {
        JsonNode page = ok("page=99&size=5");

        assertEquals(0, page.get("content").size());
        assertEquals(7, page.get("totalElements").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        assertEquals(99, page.get("page").asInt());
        assertTrue(page.get("last").asBoolean());
    }

    @Test
    void invalidPaging_isRejected() throws Exception {
        call("size=101", 400);
        call("size=0", 400);
        call("size=-5", 400);
        call("page=-1", 400);
        call("page=abc", 400);
        call("size=abc", 400);
        assertEquals(100, ok("size=100").get("size").asInt());
    }

    @Test
    void validSortFields_work() throws Exception {
        assertEquals(List.of(id(7), id(1), id(2), id(3), id(4), id(5), id(6)), ids(ok("sort=createdAt,asc")));
        assertEquals(List.of(id(5), id(3), id(2), id(1), id(6), id(4), id(7)), ids(ok("sort=grandTotal,desc")));
        assertEquals(List.of(id(7), id(4), id(6), id(1), id(2), id(3), id(5)), ids(ok("sort=grandTotal,asc")));
        assertEquals(List.of(id(7), id(6), id(5), id(4), id(3), id(2), id(1)), ids(ok("sort=orderId,desc")));
        // direction defaults to DESC when omitted
        assertEquals(List.of(id(5), id(3), id(2), id(1), id(6), id(4), id(7)), ids(ok("sort=grandTotal")));
    }

    @Test
    void invalidSort_isRejected_andNeverReachesTheQuery() throws Exception {
        call("sort=password,asc", 400);
        call("sort=user.password,asc", 400);
        call("sort=createdBy,asc", 400);
        call("sort=id,asc", 400);
        call("sort=createdAt,sideways", 400);
        call("sort=createdAt,asc,extra", 400);
        call("sort=grandTotal;drop%20table%20tbl_orders", 400);
    }

    // ---- search ----

    @Test
    void search_byOrderId_customerName_andPhone() throws Exception {
        assertIds(ok("search=" + id(3)), 3);
        assertIds(ok("search=Aaron"), 1, 3);
        assertIds(ok("search=9000000003"), 4);
        assertIds(ok("search=9000000002"), 2, 6);
    }

    @Test
    void search_isCaseInsensitive() throws Exception {
        assertIds(ok("search=aARON"), 1, 3);
        assertIds(ok("search=" + id(5).toUpperCase()), 5);
        assertIds(ok("search=walk"), 4, 5);
    }

    @Test
    void search_noMatch_isAnEmpty200Page_andWildcardsAreLiteral() throws Exception {
        JsonNode none = ok("search=nobody-matches-this");
        assertEquals(0, none.get("content").size());
        assertEquals(0, none.get("totalElements").asInt());
        assertEquals(0, none.get("totalPages").asInt());
        assertEquals(0, ok("search=%25").get("content").size());
        assertEquals(0, ok("search=_").get("content").size());
        assertEquals(7, ok("search=").get("totalElements").asInt());
    }

    @Test
    void search_tooLong_isRejected() throws Exception {
        call("search=" + "x".repeat(101), 400);
    }

    // ---- status / payment / channel ----

    @Test
    void orderStatusFilter_worksForEveryStatus_andInvalidIs400() throws Exception {
        assertIds(ok("orderStatus=PAID"), 1, 3, 4, 7);
        assertIds(ok("orderStatus=PENDING_PAYMENT"), 2);
        assertIds(ok("orderStatus=PAYMENT_FAILED"), 6);
        assertIds(ok("orderStatus=CANCELLED"), 5);
        call("orderStatus=REFUNDED", 400);
    }

    @Test
    void paymentFilters_work_andInvalidIs400() throws Exception {
        assertIds(ok("paymentMethod=CASH"), 1, 4, 7);
        assertIds(ok("paymentMethod=UPI"), 2, 3, 5, 6);
        assertIds(ok("paymentStatus=COMPLETED"), 1, 3, 4, 7);
        assertIds(ok("paymentStatus=PENDING"), 2);
        assertIds(ok("paymentStatus=FAILED"), 5, 6);
        call("paymentMethod=BITCOIN", 400);
        call("paymentStatus=MAYBE", 400);
    }

    @Test
    void salesChannelFilter_usesTheRealChannel_andInvalidIs400() throws Exception {
        assertIds(ok("salesChannel=ONLINE"), 1, 2, 6);
        assertIds(ok("salesChannel=POS"), 3, 4, 5);
        call("salesChannel=STORE", 400);
    }

    // ---- customer / staff ----

    @Test
    void customerUserId_filtersByRegisteredCustomer_notByCreator() throws Exception {
        assertIds(ok("customerUserId=" + customerA.getUserId()), 1, 3, 7);
        assertIds(ok("customerUserId=" + customerB.getUserId()), 2, 6);
        // staff accounts are not customers of anything
        assertIds(ok("customerUserId=" + cashier.getUserId()));
    }

    @Test
    void createdByUserId_filtersByStaff_notByCustomer() throws Exception {
        assertIds(ok("createdByUserId=" + cashier.getUserId()), 3, 4);
        assertIds(ok("createdByUserId=" + admin.getUserId()), 5);
        assertIds(ok("createdByUserId=" + customerA.getUserId()));
    }

    @Test
    void walkInPosOrders_haveNoCustomer_andOnlineOrdersHaveNoCreator() throws Exception {
        // Walk-in orders never match a customer filter, even one that shares their creator...
        assertIds(ok("salesChannel=POS&customerUserId=" + customerA.getUserId()), 3);
        // ...and ONLINE orders never match a creator filter.
        assertIds(ok("salesChannel=ONLINE&createdByUserId=" + cashier.getUserId()));
        assertIds(ok("salesChannel=ONLINE&createdByUserId=" + admin.getUserId()));
    }

    // ---- dates ----

    @Test
    void dateFrom_isInclusive() throws Exception {
        assertIds(ok("dateFrom=2026-01-15"), 3, 4, 5, 6);
        assertIds(ok("dateFrom=2026-01-20"), 4, 5, 6);
    }

    @Test
    void dateTo_includesTheWholeDay() throws Exception {
        // order 3 was created at 23:59 on the 15th
        assertIds(ok("dateTo=2026-01-15"), 1, 2, 3, 7);
        assertIds(ok("dateTo=2026-01-14"), 1, 2, 7);
    }

    @Test
    void dateRange_worksAndSameDayIsAllowed() throws Exception {
        assertIds(ok("dateFrom=2026-01-12&dateTo=2026-01-20"), 2, 3, 4);
        assertIds(ok("dateFrom=2026-01-20&dateTo=2026-01-20"), 4);
    }

    @Test
    void invalidDates_are400_andReversedRangeIsNotSwapped() throws Exception {
        call("dateFrom=2026-01-20&dateTo=2026-01-10", 400);
        call("dateFrom=not-a-date", 400);
        call("dateTo=15-01-2026", 400);
        call("dateFrom=2026-02-30", 400);
    }

    // ---- amounts ----

    @Test
    void amountRange_filtersOnPersistedGrandTotal() throws Exception {
        assertIds(ok("minAmount=100"), 1, 2, 3, 5);
        assertIds(ok("maxAmount=100"), 1, 4, 6, 7);
        assertIds(ok("minAmount=75&maxAmount=250"), 1, 2, 6);
        assertIds(ok("minAmount=0&maxAmount=0"));
    }

    @Test
    void invalidAmounts_are400() throws Exception {
        call("minAmount=-1", 400);
        call("maxAmount=-0.5", 400);
        call("minAmount=500&maxAmount=100", 400);
        call("minAmount=abc", 400);
        call("minAmount=NaN", 400);
        call("maxAmount=Infinity", 400);
    }

    // ---- combined ----

    @Test
    void filters_combineWithAnd() throws Exception {
        assertIds(ok("salesChannel=POS&orderStatus=PAID"), 3, 4);
        assertIds(ok("salesChannel=POS&orderStatus=PAID&paymentMethod=CASH&createdByUserId=" + cashier.getUserId()), 4);
        assertIds(ok("search=Aaron&paymentMethod=UPI&minAmount=400"), 3);
        assertIds(ok("customerUserId=" + customerA.getUserId() + "&dateFrom=2026-01-01&orderStatus=PAID"), 1, 3);
    }

    @Test
    void pagination_staysCorrectUnderFilters() throws Exception {
        JsonNode page = ok("salesChannel=POS&size=1&page=1&sort=createdAt,asc");

        assertEquals(List.of(id(4)), ids(page));
        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(3, page.get("totalPages").asInt());
        assertFalse(page.get("first").asBoolean());
        assertFalse(page.get("last").asBoolean());
    }

    @Test
    void emptyCombinedResult_isAnEmpty200Page() throws Exception {
        JsonNode page = ok("salesChannel=ONLINE&orderStatus=CANCELLED&minAmount=99999");

        assertEquals(0, page.get("content").size());
        assertEquals(0, page.get("totalElements").asInt());
        assertEquals(20, page.get("size").asInt());
        assertTrue(page.get("first").asBoolean());
        assertTrue(page.get("last").asBoolean());
    }

    @Test
    void joinedFilters_doNotDuplicateRowsOrDistortCounts() throws Exception {
        // customer + creator joins are active; each order must still appear exactly once.
        JsonNode page = ok("customerUserId=" + customerA.getUserId() + "&size=2&page=0&sort=createdAt,asc");

        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        assertEquals(List.of(id(7), id(1)), ids(page));
    }

    // ---- response content & security ----

    @Test
    void response_neverContainsSignatureSecretsOrCredentials() throws Exception {
        String raw = call("size=100", 200).getResponse().getContentAsString();
        String lower = raw.toLowerCase();

        assertFalse(lower.contains("razorpaysignature"), raw);
        assertFalse(raw.contains("SECRET_SIG_VALUE"), raw);
        assertFalse(lower.contains("password"), raw);
        assertFalse(lower.contains("not-used"), raw);
        assertFalse(lower.contains("jwt"), raw);
        assertFalse(lower.contains("secret"), raw);
        // no item lines and no payment identifiers in the list rows
        assertFalse(lower.contains("\"items\""), raw);
        assertFalse(raw.contains("pay_pub"), raw);
        // staff accounts expose only id + name
        assertFalse(raw.contains(cashier.getEmail()), raw);
        assertFalse(raw.contains(admin.getEmail()), raw);
    }

    @Test
    void customerVersusCreatorVersusChannel_areKeptDistinct() throws Exception {
        JsonNode page = ok("");

        JsonNode online = row(page, 1);
        assertEquals("ONLINE", online.get("salesChannel").asText());
        assertEquals(customerA.getUserId(), online.get("customer").get("userId").asText());
        assertTrue(online.get("createdBy").isNull());

        JsonNode posRegistered = row(page, 3);
        assertEquals("POS", posRegistered.get("salesChannel").asText());
        assertEquals(customerA.getUserId(), posRegistered.get("customer").get("userId").asText());
        assertEquals(cashier.getUserId(), posRegistered.get("createdBy").get("userId").asText());
        assertEquals(cashier.getName(), posRegistered.get("createdBy").get("name").asText());

        JsonNode walkIn = row(page, 4);
        assertEquals("POS", walkIn.get("salesChannel").asText());
        assertTrue(walkIn.get("customer").isNull());
        assertEquals(cashier.getUserId(), walkIn.get("createdBy").get("userId").asText());
        assertEquals("Walk Ina", walkIn.get("customerName").asText());

        JsonNode adminPos = row(page, 5);
        assertEquals(admin.getUserId(), adminPos.get("createdBy").get("userId").asText());

        // legacy order: channel and creator unknown, customer still known
        JsonNode legacy = row(page, 7);
        assertTrue(legacy.get("salesChannel").isNull());
        assertTrue(legacy.get("createdBy").isNull());
        assertEquals(customerA.getUserId(), legacy.get("customer").get("userId").asText());
    }

    @Test
    void historicalTotalsAndPaymentFieldsAreReportedAsStored() throws Exception {
        JsonNode three = row(ok(""), 3);

        assertEquals(499.0, three.get("subtotal").asDouble(), 0.0001);
        assertEquals(1.0, three.get("tax").asDouble(), 0.0001);
        assertEquals(500.0, three.get("grandTotal").asDouble(), 0.0001);
        assertEquals("UPI", three.get("paymentMethod").asText());
        assertEquals("COMPLETED", three.get("paymentStatus").asText());
        assertEquals("PAID", three.get("orderStatus").asText());
        assertEquals("9000000001", three.get("phoneNumber").asText());
        OrderEntity stored = orderEntityRepository.findByOrderId(id(3)).orElseThrow();
        TestMoney.assertMoney("500.0", stored.getGrandTotal());
    }

    // ---- compatibility ----

    @Test
    void existingLatestEndpoint_isUnchanged() throws Exception {
        MvcResult result = mockMvc.perform(get("/orders/latest").with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();

        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(list.isArray());
        assertEquals(7, list.size());
        assertNotNull(list.get(0).get("items"));
    }
}
