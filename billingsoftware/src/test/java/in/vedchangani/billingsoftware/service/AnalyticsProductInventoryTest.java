package in.vedchangani.billingsoftware.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Analytics batch 2: topByQuantity / topByRevenue (from order-line snapshots of PAID orders paid in
 * the range) and the point-in-time inventory summary, through the real endpoint on H2 (MySQL mode).
 * Range for every test: custom 2026-03-10 .. 2026-03-12.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsProductInventoryTest {

    private static final String CUSTOM = "range=custom&from=2026-03-10&to=2026-03-12";
    private static final double EPS = 0.00001;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;

    private UserEntity admin;
    private UserEntity customer;
    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        cleanUp();
        String s = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(UserEntity.builder().userId("uid-a-" + s).email("admin-" + s + "@example.com")
                .password("not-used").role("ROLE_ADMIN").name("Ada Admin").build());
        customer = userRepository.save(UserEntity.builder().userId("uid-c-" + s).email("cust-" + s + "@example.com")
                .password("not-used").role("ROLE_USER").name("Cara Customer").build());
        category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("CAT-" + s).name("Cat " + s).build());
    }

    @AfterEach
    void cleanUp() {
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        userRepository.deleteAll();
        if (category != null) {
            categoryRepository.deleteById(category.getId());
            category = null;
        }
    }

    // ---- helpers ----

    private static BigDecimal money(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static OrderItemEntity line(String itemId, String name, Double price, Integer quantity) {
        return OrderItemEntity.builder().itemId(itemId).name(name).price(money(price)).quantity(quantity).build();
    }

    private static LocalDateTime t(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 3, day, hour, minute, 0);
    }

    private OrderEntity order(SalesChannel channel, UserEntity registeredCustomer, OrderStatus status,
                              Double grandTotal, LocalDateTime createdAt, LocalDateTime paidAt,
                              OrderItemEntity... lines) {
        OrderEntity saved = orderEntityRepository.save(OrderEntity.builder()
                .customerName("Sensitive Customer Name").phoneNumber("9111111111")
                .grandTotal(money(grandTotal))
                .paymentMethod(paidAt != null ? PaymentMethod.UPI : PaymentMethod.CASH).orderStatus(status)
                .paymentDetails(PaymentDetails.builder().paidAt(paidAt).build())
                .items(new ArrayList<>(List.of(lines))).user(registeredCustomer).salesChannel(channel)
                .inventoryReserved(false).build());
        saved.setCreatedAt(createdAt);
        return orderEntityRepository.save(saved);
    }

    private OrderEntity paid(OrderItemEntity... lines) {
        return order(SalesChannel.POS, null, OrderStatus.PAID, 1.0, t(11, 9, 0), null, lines);
    }

    private JsonNode analytics() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/admin/analytics?" + CUSTOM)
                        .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> itemIds(JsonNode products) {
        List<String> ids = new ArrayList<>();
        products.forEach(p -> ids.add(p.get("itemId").asText()));
        return ids;
    }

    private static JsonNode product(JsonNode products, String itemId) {
        for (JsonNode p : products) {
            if (itemId.equals(p.get("itemId").asText())) {
                return p;
            }
        }
        fail("No product " + itemId + " in " + products);
        return null;
    }

    private ItemEntity item(String itemId, Integer stock, Integer reserved, Integer threshold, Boolean active) {
        return itemRepository.save(ItemEntity.builder().itemId(itemId).name("Item " + itemId)
                .price(BigDecimal.TEN).category(category)
                .stockQuantity(stock).reservedQuantity(reserved).lowStockThreshold(threshold).active(active).build());
    }

    // ---- top by quantity ----

    private void seedQuantityDataset() {
        // Paid: registered ONLINE customer and a POS walk-in both contribute; the split is irrelevant.
        paid(line("A", "Alpha", 10.0, 3), line("B", "Beta", 50.0, 1));
        order(SalesChannel.ONLINE, customer, OrderStatus.PAID, 1.0, t(10, 8, 0), null,
                line("A", "Alpha", 10.0, 2), line("C", "Gamma", 5.0, 5));
        paid(line("D", "Delta", 1.0, 5), line("E", "Epsilon", 2.0, 4), line("F", "Zeta", 3.0, 3), line("G", "Eta", 4.0, 2));
        // Excluded: not PAID
        order(SalesChannel.ONLINE, customer, OrderStatus.PENDING_PAYMENT, 1.0, t(11, 9, 0), null, line("A", "Alpha", 10.0, 100));
        order(SalesChannel.ONLINE, customer, OrderStatus.PAYMENT_FAILED, 1.0, t(11, 9, 0), null, line("B", "Beta", 50.0, 100));
        order(SalesChannel.POS, null, OrderStatus.CANCELLED, 1.0, t(11, 9, 0), null, line("C", "Gamma", 5.0, 100));
        // Excluded: PAID but outside the range (after / before)
        order(SalesChannel.POS, null, OrderStatus.PAID, 1.0, t(13, 0, 0), null, line("A", "Alpha", 10.0, 1000));
        order(SalesChannel.POS, null, OrderStatus.PAID, 1.0, t(9, 23, 59), null, line("B", "Beta", 50.0, 1000));
    }

    @Test
    void topByQuantity_aggregatesPaidLinesInRange_max5_deterministicTies() throws Exception {
        seedQuantityDataset();
        JsonNode top = analytics().get("topByQuantity");

        // A=5, C=5, D=5 (three-way tie -> itemId ascending), E=4, F=3; G=2 and B=1 fall off the top 5.
        assertEquals(List.of("A", "C", "D", "E", "F"), itemIds(top));
        assertEquals(5, product(top, "A").get("quantity").asInt());
        assertEquals(4, product(top, "E").get("quantity").asInt());
        assertEquals("Alpha", product(top, "A").get("name").asText());
        // A appears in a POS walk-in order and an ONLINE registered-customer order: 30 + 20
        assertEquals(50.0, product(top, "A").get("revenue").asDouble(), EPS);
    }

    @Test
    void topByQuantity_excludesPendingFailedCancelledAndOutOfRangeOrders() throws Exception {
        seedQuantityDataset();
        JsonNode top = analytics().get("topByQuantity");

        // 100-unit lines on non-PAID orders and 1000-unit lines outside the range would dominate if counted.
        for (JsonNode p : top) {
            assertTrue(p.get("quantity").asInt() <= 5, "unexpected quantity for " + p);
        }
    }

    @Test
    void topProducts_useEffectivePaidTime() throws Exception {
        // UPI: created before the range, paid inside it -> counted. Created inside, paid after -> not.
        order(SalesChannel.ONLINE, customer, OrderStatus.PAID, 1.0, t(9, 23, 58), t(10, 0, 3), line("IN", "Inside", 10.0, 2));
        order(SalesChannel.ONLINE, customer, OrderStatus.PAID, 1.0, t(12, 23, 58), t(13, 0, 2), line("OUT", "Outside", 10.0, 9));

        JsonNode body = analytics();

        assertEquals(List.of("IN"), itemIds(body.get("topByQuantity")));
        assertEquals(List.of("IN"), itemIds(body.get("topByRevenue")));
    }

    // ---- top by revenue / snapshot / nulls ----

    @Test
    void topByRevenue_usesHistoricalSnapshot_notCurrentCatalog() throws Exception {
        // The catalog now says P1 is "Renamed Pen" at 999; P2's item no longer exists at all.
        ItemEntity current = item("P1", 100, 0, 5, true);
        current.setName("Renamed Pen");
        current.setPrice(BigDecimal.valueOf(999));
        itemRepository.save(current);

        // grandTotal includes 1% tax (131.3); product revenue must stay pre-tax price x quantity.
        order(SalesChannel.POS, null, OrderStatus.PAID, 131.3, t(11, 9, 0), null,
                line("P1", "Pen", 10.0, 3), line("P2", "Book", 100.0, 1));
        // Same item sold later at a different snapshot price and spelled differently.
        order(SalesChannel.ONLINE, customer, OrderStatus.PAID, 40.4, t(12, 9, 0), null,
                line("P1", "Pen (old)", 20.0, 2));

        JsonNode top = analytics().get("topByRevenue");

        assertEquals(List.of("P2", "P1"), itemIds(top));
        assertEquals(100.0, product(top, "P2").get("revenue").asDouble(), EPS);   // not 131.3
        assertEquals("Book", product(top, "P2").get("name").asText());            // no catalog row at all
        assertEquals(70.0, product(top, "P1").get("revenue").asDouble(), EPS);    // 3x10 + 2x20, not 5x999
        assertEquals(5, product(top, "P1").get("quantity").asInt());
        // Representative name = MAX over the snapshot names ("Pen (old)" > "Pen"), never "Renamed Pen".
        assertEquals("Pen (old)", product(top, "P1").get("name").asText());
    }

    @Test
    void topProducts_nullPriceQuantityAndName_areHandledDeliberately() throws Exception {
        paid(line("P3", "NoPrice", null, 4),     // quantity counts, no revenue, not in topByRevenue
                line("P4", "NoQty", 5.0, null),  // excluded from both
                line("P5", null, 70.0, 1),       // no name: must not break the response
                line("P6", "Gadget", 70.0, 1));

        JsonNode body = analytics();
        JsonNode byQty = body.get("topByQuantity");
        JsonNode byRev = body.get("topByRevenue");

        assertEquals(List.of("P3", "P5", "P6"), itemIds(byQty));       // P4 has no quantity; ties on itemId
        assertEquals(0.0, product(byQty, "P3").get("revenue").asDouble(), EPS);
        assertEquals(List.of("P5", "P6"), itemIds(byRev));             // P3 has no price; tie 70/70 on itemId
        assertTrue(product(byRev, "P5").get("name").isNull());
        assertEquals(70.0, product(byRev, "P5").get("revenue").asDouble(), EPS);
    }

    @Test
    void topByRevenue_ranksByRevenueDescending_max5() throws Exception {
        seedQuantityDataset();
        JsonNode top = analytics().get("topByRevenue");

        // A=50, B=50 (tie -> A first), C=25, F=9, E=8, G=8 (tie -> E first), D=5; G and D fall off the top 5.
        assertEquals(5, top.size());
        assertEquals(List.of("A", "B", "C", "F", "E"), itemIds(top));
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).get("revenue").asDouble() >= top.get(i).get("revenue").asDouble());
        }
    }

    @Test
    void noQualifyingLines_returnsEmptyListsNotNull() throws Exception {
        order(SalesChannel.POS, null, OrderStatus.CANCELLED, 1.0, t(11, 9, 0), null, line("X", "X", 1.0, 1));

        JsonNode body = analytics();

        assertTrue(body.get("topByQuantity").isArray());
        assertEquals(0, body.get("topByQuantity").size());
        assertTrue(body.get("topByRevenue").isArray());
        assertEquals(0, body.get("topByRevenue").size());
    }

    @Test
    void topProducts_exposeNoCustomerOrOrderIdentifiers() throws Exception {
        seedQuantityDataset();
        String raw = mockMvc.perform(get("/admin/analytics?" + CUSTOM).with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFalse(raw.contains("Sensitive Customer Name"));
        assertFalse(raw.contains("9111111111"));
        assertFalse(raw.contains(customer.getEmail()));
        assertFalse(raw.contains("ORD"));
    }

    // ---- inventory ----

    @Test
    void inventorySummary_boundariesReservationInactiveAndUntracked() throws Exception {
        item("I1", 10, 0, 5, true);       // available 10 > threshold 5          -> healthy
        item("I2", 5, 0, 5, true);        // available 5 == threshold            -> low
        item("I3", 10, 6, 5, true);       // reserved reduces available to 4     -> low
        item("I4", 3, 3, 5, true);        // available 0                         -> out (not low)
        item("I5", 2, 3, 5, true);        // available -1                        -> out
        item("I6", 0, 0, 5, false);       // inactive                            -> nothing
        item("I7", 1, 0, 5, false);       // inactive, would be low if active    -> nothing
        item("I8", null, null, null, null); // legacy, never backfilled           -> untracked
        item("I9", 5, null, 5, true);     // reserved unknown                    -> untracked
        item("I10", 1, 0, null, true);    // no threshold: not low, not out, tracked
        item("I11", 1, 0, 1, true);       // available 1 == threshold 1          -> low

        JsonNode inventory = analytics().get("inventory");

        assertEquals(3, inventory.get("lowStock").asInt());     // I2, I3, I11
        assertEquals(2, inventory.get("outOfStock").asInt());   // I4, I5
        assertEquals(2, inventory.get("untracked").asInt());    // I8, I9
    }

    @Test
    void inventorySummary_ignoresDateRange_andEmptyCatalogIsZeros() throws Exception {
        JsonNode empty = analytics().get("inventory");
        assertEquals(0, empty.get("lowStock").asInt());
        assertEquals(0, empty.get("outOfStock").asInt());
        assertEquals(0, empty.get("untracked").asInt());

        item("I1", 0, 0, 5, true);
        // a range far from "now" and from the seeded data still reports the current stock state
        JsonNode other = objectMapper.readTree(mockMvc.perform(
                        get("/admin/analytics?range=custom&from=2020-01-01&to=2020-01-02")
                                .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, other.get("inventory").get("outOfStock").asInt());
    }
}
