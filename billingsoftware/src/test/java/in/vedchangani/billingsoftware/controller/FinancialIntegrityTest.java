package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.io.SalesChannel;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.OrderService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static in.vedchangani.billingsoftware.TestMoney.assertMoney;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A9, end to end on the real security chain, services and H2:
 *  - BigDecimal money: exact totals for prices that break binary floating point, stable snapshots,
 *    and the same exact figures in Dashboard, Analytics and cashier metrics
 *  - one revenue definition: PAID only, by effective paid time (paidAt, else createdAt), identical
 *    in Dashboard and Analytics across a midnight boundary
 *  - cashier My Sales is createdBy AND POS
 *  - orders (paid or not) cannot be hard-deleted, and their audit trail stays
 *
 * Each test starts from an empty order table so the store-wide reports only see its own data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinancialIntegrityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private OrderService orderService;

    private String s;
    private UserEntity admin;
    private UserEntity cashier;
    private UserEntity otherCashier;
    private UserEntity customer;
    private CategoryEntity category;
    private ItemEntity dime;      // 0.10
    private ItemEntity fifth;     // 0.20
    private ItemEntity small;     // 10.99
    private ItemEntity large;     // 19.99

    @BeforeEach
    void setUp() {
        orderEntityRepository.deleteAll();
        s = UUID.randomUUID().toString().substring(0, 8);
        admin = account("Ada Admin", "ROLE_ADMIN");
        cashier = account("Casey Cashier", "ROLE_CASHIER");
        otherCashier = account("Drew Cashier", "ROLE_CASHIER");
        customer = account("Cara Customer", "ROLE_USER");
        category = categoryRepository.save(CategoryEntity.builder().categoryId("fi-cat-" + s).name("Money " + s).build());
        dime = item("dime", "0.10");
        fifth = item("fifth", "0.20");
        small = item("small", "10.99");
        large = item("large", "19.99");
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity account(String name, String role) {
        return userRepository.save(UserEntity.builder().userId("uid-" + UUID.randomUUID())
                .email(role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .name(name).role(role).mobile(TestMobiles.next()).password("not-used").build());
    }

    private ItemEntity item(String id, String price) {
        return itemRepository.save(ItemEntity.builder().itemId("fi-" + id + "-" + s).name(id).price(new BigDecimal(price))
                .category(category).stockQuantity(1000).reservedQuantity(0).lowStockThreshold(5).active(true).build());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserEntity actor) {
        return request.with(user(actor.getEmail()).roles(actor.getRole().replace("ROLE_", "")));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // 3 x 0.10 + 3 x 0.20 + 1 x 10.99 + 1 x 19.99: subtotal 31.88, tax 0.3188 -> 0.32, total 32.20
    private String mixedCart() {
        return "[{\"itemId\":\"" + dime.getItemId() + "\",\"quantity\":3},{\"itemId\":\"" + fifth.getItemId()
                + "\",\"quantity\":3},{\"itemId\":\"" + small.getItemId() + "\",\"quantity\":1},{\"itemId\":\""
                + large.getItemId() + "\",\"quantity\":1}]";
    }

    private MvcResult placeOnline(String method) throws Exception {
        return perform(as(post("/orders"), customer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"" + method + "\",\"cartItems\":" + mixedCart() + "}"));
    }

    private MvcResult placePos(UserEntity by, String method) throws Exception {
        return perform(as(post("/pos/orders"), by).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"" + method + "\",\"cartItems\":" + mixedCart() + "}"));
    }

    // A directly seeded order with a fixed status and timestamps (for revenue-date semantics).
    private OrderEntity seed(PaymentMethod method, OrderStatus status, String grandTotal,
                             LocalDateTime createdAt, LocalDateTime paidAt, SalesChannel channel, UserEntity createdBy) {
        List<OrderItemEntity> lines = new ArrayList<>();
        lines.add(OrderItemEntity.builder().itemId(small.getItemId()).name("small").price(new BigDecimal(grandTotal)).quantity(1).build());
        OrderEntity order = orderEntityRepository.save(OrderEntity.builder()
                .customerName("Walk-in").phoneNumber("9000000000")
                .subtotal(new BigDecimal(grandTotal)).tax(BigDecimal.ZERO).grandTotal(new BigDecimal(grandTotal))
                .paymentMethod(method).orderStatus(status)
                .paymentDetails(PaymentDetails.builder().paidAt(paidAt)
                        .status(status == OrderStatus.PAID ? PaymentDetails.PaymentStatus.COMPLETED : PaymentDetails.PaymentStatus.PENDING).build())
                .items(lines).salesChannel(channel).createdBy(createdBy).user(channel == SalesChannel.ONLINE ? customer : null)
                .inventoryReserved(false).build());
        order.setCreatedAt(createdAt);
        return orderEntityRepository.save(order);
    }

    private JsonNode analyticsFor(LocalDate day) throws Exception {
        MvcResult result = perform(as(get("/admin/analytics").param("range", "custom")
                .param("from", day.toString()).param("to", day.toString()), admin));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result);
    }

    // =====================================================================================
    // BigDecimal money
    // =====================================================================================

    @Test
    void orderTotals_areExactDecimals_forPricesThatBreakFloatingPoint() throws Exception {
        MvcResult result = placeOnline("CASH");

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        String raw = result.getResponse().getContentAsString();
        // exact plain decimals in the API - no 31.880000000000003 / 32.199999999999996 artifacts
        assertTrue(raw.contains("\"subtotal\":31.88"), raw);
        assertTrue(raw.contains("\"tax\":0.32"), raw);
        assertTrue(raw.contains("\"grandTotal\":32.20"), raw);
        // no money field carries more than 2 decimals (timestamps are excluded on purpose)
        assertFalse(raw.matches("(?s).*\"(price|lineTotal|subtotal|tax|grandTotal)\":-?\\d+\\.\\d{3,}.*"), raw);
        JsonNode order = body(result);
        assertMoney("0.30", order.get("items").get(0).get("lineTotal").decimalValue());
        assertMoney("0.60", order.get("items").get(1).get("lineTotal").decimalValue());

        OrderEntity stored = orderEntityRepository.findByOrderId(order.get("orderId").asText()).orElseThrow();
        assertMoney("31.88", stored.getSubtotal());
        assertMoney("0.32", stored.getTax());
        assertMoney("32.20", stored.getGrandTotal());
    }

    @Test
    void orderItemPrice_isAHistoricalSnapshot() throws Exception {
        String orderId = body(placeOnline("CASH")).get("orderId").asText();

        ItemEntity repriced = itemRepository.findByItemId(small.getItemId()).orElseThrow();
        repriced.setPrice(new BigDecimal("99.99"));
        itemRepository.save(repriced);

        JsonNode detail = body(perform(as(get("/orders/" + orderId), customer)));
        JsonNode smallLine = null;
        for (JsonNode line : detail.get("items")) {
            if (small.getItemId().equals(line.get("itemId").asText())) smallLine = line;
        }
        assertNotNull(smallLine);
        assertMoney("10.99", smallLine.get("price").decimalValue());
        assertMoney("32.20", detail.get("grandTotal").decimalValue());
    }

    @Test
    void itemPrices_withMoreThanTwoDecimals_areRejected() throws Exception {
        MvcResult result = perform(as(put("/admin/items/" + small.getItemId()), admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":10.999}"));
        assertEquals(400, result.getResponse().getStatus());
        assertMoney("10.99", itemRepository.findByItemId(small.getItemId()).orElseThrow().getPrice());
    }

    @Test
    void dashboardAnalyticsAndCashierRevenue_allReportTheSameExactTotals() throws Exception {
        assertEquals(201, placeOnline("CASH").getResponse().getStatus());            // 32.20 ONLINE
        assertEquals(201, placePos(cashier, "CASH").getResponse().getStatus());     // 32.20 POS
        assertEquals(201, placePos(cashier, "CASH").getResponse().getStatus());     // 32.20 POS
        assertEquals(201, placePos(cashier, "UPI").getResponse().getStatus());      // pending: not revenue

        JsonNode dashboard = body(perform(as(get("/dashboard"), admin)));
        assertMoney("96.60", dashboard.get("todaySales").decimalValue());
        assertEquals(3, dashboard.get("todayOrderCount").asLong());

        JsonNode analytics = analyticsFor(LocalDate.now());
        assertMoney("96.60", analytics.get("kpis").get("revenue").decimalValue());
        assertEquals(3, analytics.get("kpis").get("paidOrders").asLong());
        assertMoney("32.20", analytics.get("kpis").get("averageOrderValue").decimalValue());
        for (JsonNode channel : analytics.get("channels")) {
            if ("POS".equals(channel.get("channel").asText())) assertMoney("64.40", channel.get("revenue").decimalValue());
            if ("ONLINE".equals(channel.get("channel").asText())) assertMoney("32.20", channel.get("revenue").decimalValue());
        }
        // top product revenue is pre-tax line revenue: 19.99 x 3 paid orders
        assertMoney("59.97", analytics.get("topByRevenue").get(0).get("revenue").decimalValue());

        JsonNode cashiers = body(perform(as(get("/admin/cashiers"), admin)));
        for (JsonNode row : cashiers) {
            if (cashier.getUserId().equals(row.get("userId").asText())) {
                assertMoney("64.40", row.get("posRevenue").decimalValue());   // PAID only
                assertEquals(3, row.get("ordersProcessed").asLong());
            }
        }
        String raw = perform(as(get("/admin/analytics").param("range", "today"), admin)).getResponse().getContentAsString();
        assertFalse(raw.matches("(?s).*\"revenue\":\\d+\\.\\d{3,}.*"), raw);
    }

    // =====================================================================================
    // One revenue definition: PAID only, by effective paid time
    // =====================================================================================

    @Test
    void dashboardAndAnalytics_classifyEveryOrderOnTheSameDay_acrossMidnight() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 3, 19);
        LocalDate day2 = LocalDate.of(2026, 3, 20);
        // CASH on day 1: paidAt null -> createdAt -> day 1
        seed(PaymentMethod.CASH, OrderStatus.PAID, "10.10", day1.atTime(10, 0), null, SalesChannel.ONLINE, null);
        // UPI created day 1 23:59, verified day 2 00:01 -> day 2
        seed(PaymentMethod.UPI, OrderStatus.PAID, "20.20", day1.atTime(23, 59), day2.atTime(0, 1), SalesChannel.ONLINE, null);
        // never revenue: unverified / failed / cancelled
        seed(PaymentMethod.UPI, OrderStatus.PENDING_PAYMENT, "40.40", day1.atTime(12, 0), null, SalesChannel.ONLINE, null);
        seed(PaymentMethod.UPI, OrderStatus.PAYMENT_FAILED, "50.50", day1.atTime(13, 0), null, SalesChannel.ONLINE, null);
        seed(PaymentMethod.UPI, OrderStatus.CANCELLED, "60.60", day2.atTime(13, 0), null, SalesChannel.ONLINE, null);

        // Dashboard
        assertMoney("10.10", orderService.sumSalesByDate(day1));
        assertEquals(1L, orderService.countByOrderDate(day1));
        assertMoney("20.20", orderService.sumSalesByDate(day2));
        assertEquals(1L, orderService.countByOrderDate(day2));
        // Analytics - identical classification
        JsonNode a1 = analyticsFor(day1).get("kpis");
        JsonNode a2 = analyticsFor(day2).get("kpis");
        assertMoney("10.10", a1.get("revenue").decimalValue());
        assertEquals(1, a1.get("paidOrders").asLong());
        assertMoney("20.20", a2.get("revenue").decimalValue());
        assertEquals(1, a2.get("paidOrders").asLong());
        // a day with no paid orders is zero in both
        assertMoney("0", orderService.sumSalesByDate(day2.plusDays(1)));
        assertMoney("0", analyticsFor(day2.plusDays(1)).get("kpis").get("revenue").decimalValue());
    }

    @Test
    void upiOrder_contributesOnlyAfterVerification() throws Exception {
        LocalDate day = LocalDate.of(2026, 4, 1);
        OrderEntity upi = seed(PaymentMethod.UPI, OrderStatus.PENDING_PAYMENT, "30.30", day.atTime(9, 0), null,
                SalesChannel.ONLINE, null);
        assertMoney("0", orderService.sumSalesByDate(day));

        // verified later the same day -> counts on the paid time
        upi.setOrderStatus(OrderStatus.PAID);
        upi.getPaymentDetails().setPaidAt(day.atTime(9, 5));
        orderEntityRepository.save(upi);

        assertMoney("30.30", orderService.sumSalesByDate(day));
        assertMoney("30.30", analyticsFor(day).get("kpis").get("revenue").decimalValue());
    }

    @Test
    void cashierRevenue_countsOnlyPaidPosSalesOfThatCashier() throws Exception {
        LocalDate day = LocalDate.of(2026, 5, 5);
        seed(PaymentMethod.CASH, OrderStatus.PAID, "10.10", day.atTime(9, 0), null, SalesChannel.POS, cashier);
        seed(PaymentMethod.UPI, OrderStatus.PAID, "20.20", day.atTime(9, 0), day.atTime(9, 1), SalesChannel.POS, cashier);
        seed(PaymentMethod.UPI, OrderStatus.PAYMENT_FAILED, "99.99", day.atTime(9, 0), null, SalesChannel.POS, cashier);
        seed(PaymentMethod.UPI, OrderStatus.CANCELLED, "88.88", day.atTime(9, 0), null, SalesChannel.POS, cashier);
        seed(PaymentMethod.CASH, OrderStatus.PAID, "77.77", day.atTime(9, 0), null, SalesChannel.POS, otherCashier);

        for (JsonNode row : body(perform(as(get("/admin/cashiers"), admin)))) {
            if (cashier.getUserId().equals(row.get("userId").asText())) {
                assertMoney("30.30", row.get("posRevenue").decimalValue());
                assertEquals(4, row.get("ordersProcessed").asLong());
            }
        }
    }

    // =====================================================================================
    // My Sales: createdBy AND POS
    // =====================================================================================

    @Test
    void mySales_requiresBothCreatedByAndPos_evenWithBadHistoricalData() throws Exception {
        String ownPos = body(placePos(cashier, "CASH")).get("orderId").asText();
        String othersPos = body(placePos(otherCashier, "CASH")).get("orderId").asText();
        // corrupt historical row: an ONLINE order that (wrongly) carries this cashier as createdBy
        OrderEntity badOnline = seed(PaymentMethod.CASH, OrderStatus.PAID, "5.05", LocalDateTime.now(), null,
                SalesChannel.ONLINE, cashier);
        // legacy row with no channel at all but this cashier as creator
        OrderEntity legacy = seed(PaymentMethod.CASH, OrderStatus.PAID, "6.06", LocalDateTime.now(), null, null, cashier);

        MvcResult result = perform(as(get("/pos/sales").param("createdByUserId", otherCashier.getUserId()), cashier));

        assertEquals(200, result.getResponse().getStatus());
        String raw = result.getResponse().getContentAsString();
        assertTrue(raw.contains(ownPos));
        assertFalse(raw.contains(othersPos));
        assertFalse(raw.contains(badOnline.getOrderId()));
        assertFalse(raw.contains(legacy.getOrderId()));
        assertEquals(1, body(result).size());
    }

    // =====================================================================================
    // No hard deletion of orders
    // =====================================================================================

    @Test
    void paidOrders_cannotBeHardDeleted_byAnyone_andStayQueryableWithTheirAuditTrail() throws Exception {
        String paid = body(placeOnline("CASH")).get("orderId").asText();
        String pending = body(placePos(cashier, "UPI")).get("orderId").asText();
        String auditBefore = perform(as(get("/admin/activity").param("action", "ONLINE_ORDER_CREATED")
                .param("actorUserId", customer.getUserId()), admin)).getResponse().getContentAsString();
        assertTrue(auditBefore.contains(paid));

        for (String orderId : new String[]{paid, pending}) {
            assertEquals(405, perform(as(delete("/orders/" + orderId), admin)).getResponse().getStatus());
            assertEquals(405, perform(as(delete("/orders/" + orderId), cashier)).getResponse().getStatus());
            assertEquals(405, perform(as(delete("/orders/" + orderId), customer)).getResponse().getStatus());
            assertEquals(401, perform(delete("/orders/" + orderId)).getResponse().getStatus());
            assertEquals(404, perform(as(delete("/admin/orders/" + orderId), admin)).getResponse().getStatus());
            assertTrue(orderEntityRepository.findByOrderId(orderId).isPresent());
        }

        // still visible to the admin, the customer and the cashier, with totals intact
        String all = perform(as(get("/admin/orders").param("size", "100"), admin)).getResponse().getContentAsString();
        assertTrue(all.contains(paid));
        assertTrue(all.contains(pending));
        assertMoney("32.20", body(perform(as(get("/orders/" + paid), customer))).get("grandTotal").decimalValue());
        assertEquals(200, perform(as(get("/orders/" + pending), cashier)).getResponse().getStatus());
        // audit trail untouched, and no deletion was recorded
        assertEquals(auditBefore, perform(as(get("/admin/activity").param("action", "ONLINE_ORDER_CREATED")
                .param("actorUserId", customer.getUserId()), admin)).getResponse().getContentAsString());
        assertEquals(0, body(perform(as(get("/admin/activity").param("action", "ORDER_DELETED")
                .param("actorUserId", admin.getUserId()), admin))).get("totalElements").asLong());
    }
}
