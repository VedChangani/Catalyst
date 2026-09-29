package in.vedchangani.billingsoftware.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.io.SalesChannel;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsDashboardTest {

    private static final String CUSTOM = "range=custom&from=2026-03-10&to=2026-03-12";
    private static final double EPS = 0.00001;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private DataSource dataSource;

    private UserEntity admin;
    private int seq;

    @BeforeEach
    void setUp() {
        orderEntityRepository.deleteAll();
        userRepository.deleteAll();
        String s = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(UserEntity.builder().userId("uid-" + s).email("admin-" + s + "@example.com")
                .password("not-used").role("ROLE_ADMIN").name("Ada Admin").build());
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static BigDecimal money(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private OrderEntity seed(SalesChannel channel, PaymentMethod method, OrderStatus status, Double grandTotal,
                             LocalDateTime createdAt, LocalDateTime paidAt) {
        seq++;
        List<OrderItemEntity> lines = new ArrayList<>();
        lines.add(OrderItemEntity.builder().itemId("i-" + seq).name("Coffee").price(new BigDecimal("1.0")).quantity(1).build());
        OrderEntity order = orderEntityRepository.save(OrderEntity.builder()
                .customerName("Sensitive Customer Name").phoneNumber("9111111111")
                .subtotal(money(grandTotal)).tax(new BigDecimal("0.0")).grandTotal(money(grandTotal))
                .paymentMethod(method).orderStatus(status)
                .paymentDetails(PaymentDetails.builder().paidAt(paidAt).build())
                .items(lines).salesChannel(channel).inventoryReserved(false).build());
        order.setCreatedAt(createdAt);
        return orderEntityRepository.save(order);
    }

    private static LocalDateTime t(int day, int hour, int minute, int second) {
        return LocalDateTime.of(2026, 3, day, hour, minute, second);
    }

    private void seedFixedDataset() {
        seed(SalesChannel.ONLINE, PaymentMethod.UPI, OrderStatus.PAID, 100.0, t(9, 23, 58, 0), t(10, 0, 3, 0));
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 50.0, t(10, 12, 0, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 200.0, t(12, 23, 59, 59), null);
        seed(SalesChannel.ONLINE, PaymentMethod.UPI, OrderStatus.PAID, 300.0, t(13, 0, 0, 0), t(13, 0, 0, 5));
        seed(SalesChannel.ONLINE, PaymentMethod.CASH, OrderStatus.PAID, 70.0, t(9, 23, 59, 59), null);
        seed(SalesChannel.ONLINE, PaymentMethod.UPI, OrderStatus.PENDING_PAYMENT, 999.0, t(11, 9, 0, 0), null);
        seed(SalesChannel.ONLINE, PaymentMethod.UPI, OrderStatus.PAYMENT_FAILED, 888.0, t(11, 9, 0, 0), null);
        seed(SalesChannel.POS, PaymentMethod.UPI, OrderStatus.CANCELLED, 777.0, t(11, 9, 0, 0), null);
        seed(null, null, OrderStatus.PAID, 30.0, t(11, 9, 0, 0), null);
        seed(SalesChannel.ONLINE, PaymentMethod.CASH, null, 555.0, t(11, 9, 0, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, null, t(11, 9, 0, 0), null);
        seed(SalesChannel.ONLINE, PaymentMethod.UPI, OrderStatus.PAID, 40.0, t(11, 8, 0, 0), t(11, 10, 0, 0));
    }

    private MvcResult call(String query, int expectedStatus) throws Exception {
        return mockMvc.perform(get("/admin/analytics" + (query.isEmpty() ? "" : "?" + query))
                        .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode ok(String query) throws Exception {
        return objectMapper.readTree(call(query, 200).getResponse().getContentAsString());
    }

    private static JsonNode byKey(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.get(field).asText())) {
                return n;
            }
        }
        fail("No " + field + "=" + value + " in " + array);
        return null;
    }

    @Test
    void revenue_countsOnlyPaidOrders_byEffectivePaidTime() throws Exception {
        seedFixedDataset();
        JsonNode body = ok(CUSTOM);

        JsonNode kpis = body.get("kpis");
        assertEquals(420.0, kpis.get("revenue").asDouble(), EPS);
        assertEquals(6, kpis.get("paidOrders").asInt());
        assertEquals(70.0, kpis.get("averageOrderValue").asDouble(), EPS);
    }

    @Test
    void cashPaidWithNullPaidAt_fallsBackToCreatedAt_andUpiUsesPaidAt() throws Exception {
        seedFixedDataset();
        JsonNode daily = ok(CUSTOM).get("daily");

        assertEquals(3, daily.size());
        assertEquals("2026-03-10", daily.get(0).get("date").asText());
        assertEquals(150.0, daily.get(0).get("revenue").asDouble(), EPS);
        assertEquals(2, daily.get(0).get("orders").asInt());
        assertEquals(70.0, daily.get(1).get("revenue").asDouble(), EPS);
        assertEquals(3, daily.get(1).get("orders").asInt());
        assertEquals(200.0, daily.get(2).get("revenue").asDouble(), EPS);
        assertEquals(1, daily.get(2).get("orders").asInt());
    }

    @Test
    void upiOrderCreatedInRangeButPaidAfterIt_isNotRevenueInThatRange() throws Exception {
        seed(SalesChannel.ONLINE, PaymentMethod.UPI, OrderStatus.PAID, 500.0, t(12, 23, 58, 0), t(13, 0, 2, 0));

        JsonNode body = ok(CUSTOM);

        assertEquals(0.0, body.get("kpis").get("revenue").asDouble(), EPS);
        assertEquals(0, body.get("kpis").get("paidOrders").asInt());
        assertEquals(1, body.get("orderStatusCounts").get("PAID").asInt());
    }

    @Test
    void dateBoundaries_startInclusive_endDayInclusive_nextMidnightExcluded() throws Exception {
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 1.0, t(10, 0, 0, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 10.0, t(12, 23, 59, 59), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 100.0, t(13, 0, 0, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 1000.0, t(9, 23, 59, 59), null);

        assertEquals(11.0, ok(CUSTOM).get("kpis").get("revenue").asDouble(), EPS);
    }

    @Test
    void channelBreakdown_includesUnknownBucket_andShares() throws Exception {
        seedFixedDataset();
        JsonNode channels = ok(CUSTOM).get("channels");

        assertEquals(3, channels.size());
        JsonNode online = byKey(channels, "channel", "ONLINE");
        JsonNode pos = byKey(channels, "channel", "POS");
        JsonNode unknown = byKey(channels, "channel", "UNKNOWN");
        assertEquals(140.0, online.get("revenue").asDouble(), EPS);
        assertEquals(2, online.get("orders").asInt());
        assertEquals(250.0, pos.get("revenue").asDouble(), EPS);
        assertEquals(3, pos.get("orders").asInt());
        assertEquals(30.0, unknown.get("revenue").asDouble(), EPS);
        assertEquals(1, unknown.get("orders").asInt());
        assertEquals(140.0 / 420.0, online.get("revenueShare").asDouble(), 0.0001);
        assertEquals(250.0 / 420.0, pos.get("revenueShare").asDouble(), 0.0001);
        assertEquals(30.0 / 420.0, unknown.get("revenueShare").asDouble(), 0.0001);
        assertEquals(3.0 / 6.0, pos.get("orderShare").asDouble(), 0.0001);
    }

    @Test
    void paymentMethodBreakdown_includesUnknownBucket() throws Exception {
        seedFixedDataset();
        JsonNode methods = ok(CUSTOM).get("paymentMethods");

        assertEquals(3, methods.size());
        JsonNode cash = byKey(methods, "method", "CASH");
        JsonNode upi = byKey(methods, "method", "UPI");
        JsonNode unknown = byKey(methods, "method", "UNKNOWN");
        assertEquals(250.0, cash.get("revenue").asDouble(), EPS);
        assertEquals(3, cash.get("orders").asInt());
        assertEquals(140.0, upi.get("revenue").asDouble(), EPS);
        assertEquals(2, upi.get("orders").asInt());
        assertEquals(30.0, unknown.get("revenue").asDouble(), EPS);
        assertEquals(1, unknown.get("orders").asInt());
    }

    @Test
    void statusCounts_useCreatedAt_zeroFillAllStatuses_andIgnoreNullStatus() throws Exception {
        seedFixedDataset();
        JsonNode body = ok(CUSTOM);
        JsonNode counts = body.get("orderStatusCounts");

        assertEquals(5, counts.get("PAID").asInt());
        assertEquals(1, counts.get("PENDING_PAYMENT").asInt());
        assertEquals(1, counts.get("PAYMENT_FAILED").asInt());
        assertEquals(1, counts.get("CANCELLED").asInt());
        assertEquals(4, counts.size(), "no bogus bucket for the NULL-status legacy row");
        assertEquals(1.0 / 3.0, body.get("upiSuccessRate").asDouble(), 0.0001);
    }

    @Test
    void emptyDataset_returnsZerosAndZeroFilledSeries() throws Exception {
        JsonNode body = ok(CUSTOM);

        assertEquals(0.0, body.get("kpis").get("revenue").asDouble(), EPS);
        assertEquals(0, body.get("kpis").get("paidOrders").asInt());
        assertEquals(0.0, body.get("kpis").get("averageOrderValue").asDouble(), EPS);
        assertEquals(3, body.get("daily").size());
        for (JsonNode day : body.get("daily")) {
            assertEquals(0.0, day.get("revenue").asDouble(), EPS);
            assertEquals(0, day.get("orders").asInt());
        }
        assertEquals(3, body.get("channels").size());
        for (JsonNode c : body.get("channels")) {
            assertEquals(0.0, c.get("revenueShare").asDouble(), EPS);
            assertEquals(0.0, c.get("orderShare").asDouble(), EPS);
        }
        assertEquals(3, body.get("paymentMethods").size());
        for (JsonNode s : body.get("orderStatusCounts")) {
            assertEquals(0, s.asInt());
        }
        assertTrue(body.get("upiSuccessRate").isNull());
    }

    @Test
    void zeroRevenueWithPaidOrders_hasZeroRevenueShares_notNaN() throws Exception {
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, null, t(11, 9, 0, 0), null);
        seed(SalesChannel.ONLINE, PaymentMethod.CASH, OrderStatus.PAID, 0.0, t(11, 9, 0, 0), null);

        JsonNode body = ok(CUSTOM);

        assertEquals(2, body.get("kpis").get("paidOrders").asInt());
        assertEquals(0.0, body.get("kpis").get("revenue").asDouble(), EPS);
        assertEquals(0.0, body.get("kpis").get("averageOrderValue").asDouble(), EPS);
        JsonNode pos = byKey(body.get("channels"), "channel", "POS");
        assertEquals(0.0, pos.get("revenueShare").asDouble(), EPS);
        assertEquals(0.5, pos.get("orderShare").asDouble(), EPS);
    }

    @Test
    void response_containsNoCustomerOrOrderIdentifiers() throws Exception {
        seedFixedDataset();
        String raw = call(CUSTOM, 200).getResponse().getContentAsString();

        assertFalse(raw.contains("Sensitive Customer Name"));
        assertFalse(raw.contains("9111111111"));
        assertFalse(raw.contains("ORD"));
        assertFalse(raw.toLowerCase().contains("razorpay"));
    }

    @Test
    void presets_resolveAndFilter_relativeToToday() throws Exception {
        LocalDate today = LocalDate.now();
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 1.0, today.atTime(12, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 10.0, today.minusDays(1).atTime(12, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 100.0, today.minusDays(6).atTime(12, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 1000.0, today.minusDays(7).atTime(12, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 10000.0, today.minusDays(29).atTime(12, 0), null);
        seed(SalesChannel.POS, PaymentMethod.CASH, OrderStatus.PAID, 100000.0, today.minusDays(30).atTime(12, 0), null);

        JsonNode todayBody = ok("range=today");
        assertEquals("today", todayBody.get("range").get("preset").asText());
        assertEquals(today.toString(), todayBody.get("range").get("from").asText());
        assertEquals(today.toString(), todayBody.get("range").get("to").asText());
        assertEquals(1.0, todayBody.get("kpis").get("revenue").asDouble(), EPS);
        assertEquals(1, todayBody.get("daily").size());

        JsonNode week = ok("range=7d");
        assertEquals(today.minusDays(6).toString(), week.get("range").get("from").asText());
        assertEquals(111.0, week.get("kpis").get("revenue").asDouble(), EPS);
        assertEquals(7, week.get("daily").size());

        JsonNode dflt = ok("");
        assertEquals("7d", dflt.get("range").get("preset").asText());
        assertEquals(111.0, dflt.get("kpis").get("revenue").asDouble(), EPS);

        JsonNode month = ok("range=30d");
        assertEquals(today.minusDays(29).toString(), month.get("range").get("from").asText());
        assertEquals(11111.0, month.get("kpis").get("revenue").asDouble(), EPS);
        assertEquals(30, month.get("daily").size());
    }

    @Test
    void invalidRanges_areRejectedWith400() throws Exception {
        call("range=custom", 400);
        call("range=custom&from=2026-03-10", 400);
        call("range=custom&from=2026-03-12&to=2026-03-10", 400);
        call("range=custom&from=2025-01-01&to=2026-03-10", 400);
        call("range=yesterday", 400);
        call("range=7d&from=2026-03-10&to=2026-03-12", 400);
        call("range=custom&from=not-a-date&to=2026-03-12", 400);
        call("range=custom&from=2025-03-12&to=2026-03-12", 200);
    }

    @Test
    void analyticsIndex_existsOnOrdersTable_withStatusThenCreatedAt() throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.getMetaData().getIndexInfo(null, null, "TBL_ORDERS", false, false)) {
            while (rs.next()) {
                if ("idx_tbl_orders_status_created".equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    assertTrue(rs.getBoolean("NON_UNIQUE"), "analytics index must not be unique");
                    columns.add(rs.getInt("ORDINAL_POSITION") + ":" + rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
        }
        columns.sort(String::compareTo);
        assertEquals(List.of("1:order_status", "2:created_at"), columns);
    }
}
