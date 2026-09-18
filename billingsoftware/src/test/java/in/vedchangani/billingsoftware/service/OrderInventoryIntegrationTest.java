package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end createOrder inventory behavior against the H2 "test" datasource, through the real
 * transactional OrderService proxy: real reserve/commit/release UPDATEs, real rollback, and the
 * real stale-reservation query.
 *
 * Deliberately NOT @Transactional: the service's own transaction must commit or roll back for
 * real, so assertions read what was actually persisted.
 *
 * H2 runs these sequentially and does not model MySQL InnoDB row locking, so nothing here claims
 * to prove concurrent-request correctness - that remains a dedicated MySQL-backed follow-up.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderInventoryIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderEntityRepository orderEntityRepository;

    private UserEntity user;
    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        user = userRepository.save(UserEntity.builder()
                .userId("inv-it-" + suffix)
                .email("inventory-it-" + suffix + "@example.com")
                .password("not-used")
                .role("ROLE_USER")
                .name("Inventory IT")
                .build());
        category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("inv-it-cat-" + suffix)
                .name("Inventory IT " + suffix)
                .build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
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

    private ItemEntity anItem(String itemIdPrefix, String name, Integer stock, Integer reserved, Boolean active) {
        return itemRepository.save(ItemEntity.builder()
                .itemId(itemIdPrefix + "-" + UUID.randomUUID())
                .name(name)
                .price(BigDecimal.valueOf(10))
                .category(category)
                .stockQuantity(stock)
                .reservedQuantity(reserved)
                .lowStockThreshold(stock == null ? null : 5)
                .active(active)
                .build());
    }

    private ItemEntity reload(ItemEntity item) {
        return itemRepository.findByItemId(item.getItemId()).orElseThrow();
    }

    private OrderRequest aRequest(String paymentMethod, OrderRequest.OrderItemRequest... lines) {
        return OrderRequest.builder()
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .paymentMethod(paymentMethod)
                .cartItems(Arrays.asList(lines))
                .build();
    }

    private OrderRequest.OrderItemRequest line(ItemEntity item, int quantity) {
        return new OrderRequest.OrderItemRequest(item.getItemId(), quantity);
    }

    private OrderEntity findCreatedOrder(OrderResponse response) {
        return orderEntityRepository.findAll().stream()
                .filter(o -> o.getOrderId().equals(response.getOrderId()))
                .findFirst()
                .orElseThrow();
    }

    // Seeds a UPI order in PENDING_PAYMENT as if it had been created (and had reserved stock)
    // at the given time. The matching reservedQuantity must be seeded on the items separately.
    private OrderEntity aPendingUpiOrder(LocalDateTime createdAt, Boolean inventoryReserved, OrderItemEntity... lines) {
        OrderEntity order = orderEntityRepository.save(OrderEntity.builder()
                .customerName("Earlier Customer")
                .phoneNumber("8888888888")
                .subtotal(10.0).tax(0.1).grandTotal(10.1)
                .paymentMethod(PaymentMethod.UPI)
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .paymentDetails(PaymentDetails.builder().status(PaymentDetails.PaymentStatus.PENDING).build())
                .inventoryReserved(inventoryReserved)
                .user(user)
                .items(new ArrayList<>(Arrays.asList(lines)))
                .build());
        // @PrePersist stamps "now" and a millisecond-based orderId; back-date it and give it an
        // id that can't collide with an order the test is about to create.
        order.setCreatedAt(createdAt);
        order.setOrderId("SEED-" + UUID.randomUUID());
        return orderEntityRepository.save(order);
    }

    private OrderItemEntity orderLine(ItemEntity item, int quantity) {
        return OrderItemEntity.builder()
                .itemId(item.getItemId()).name(item.getName()).price(10.0).quantity(quantity)
                .build();
    }

    // ---- 1. CASH, sufficient stock ----

    @Test
    void cashOrder_withSufficientStock_isPaid_reducesStock_andLeavesNothingReserved() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, true);

        OrderResponse response = orderService.createOrder(aRequest("CASH", line(burger, 3)));

        assertEquals(OrderStatus.PAID, response.getOrderStatus());
        ItemEntity after = reload(burger);
        assertEquals(7, after.getStockQuantity());
        assertEquals(0, after.getReservedQuantity());
        assertEquals(Boolean.FALSE, findCreatedOrder(response).getInventoryReserved());
    }

    // ---- 2. CASH, insufficient stock ----

    @Test
    void cashOrder_withInsufficientStock_isConflict_createsNoOrder_andMutatesNoStock() {
        ItemEntity burger = anItem("burger", "Burger", 1, 0, true);
        long ordersBefore = orderEntityRepository.count();

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("CASH", line(burger, 2))));

        assertEquals(ordersBefore, orderEntityRepository.count());
        ItemEntity after = reload(burger);
        assertEquals(1, after.getStockQuantity());
        assertEquals(0, after.getReservedQuantity());
    }

    // ---- 3. UPI, sufficient stock ----

    @Test
    void upiOrder_withSufficientStock_isPending_keepsStock_andReservesQuantity() {
        ItemEntity burger = anItem("burger", "Burger", 10, 1, true);

        OrderResponse response = orderService.createOrder(aRequest("UPI", line(burger, 4)));

        assertEquals(OrderStatus.PENDING_PAYMENT, response.getOrderStatus());
        ItemEntity after = reload(burger);
        assertEquals(10, after.getStockQuantity());
        assertEquals(5, after.getReservedQuantity());
        assertEquals(Boolean.TRUE, findCreatedOrder(response).getInventoryReserved());
    }

    // ---- 4. UPI, insufficient stock (already-reserved units are not available) ----

    @Test
    void upiOrder_withInsufficientAvailableStock_isConflict_andLeavesNoReservation() {
        ItemEntity burger = anItem("burger", "Burger", 5, 4, true); // available = 1
        long ordersBefore = orderEntityRepository.count();

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("UPI", line(burger, 2))));

        assertEquals(ordersBefore, orderEntityRepository.count());
        ItemEntity after = reload(burger);
        assertEquals(5, after.getStockQuantity());
        assertEquals(4, after.getReservedQuantity());
    }

    // ---- 5. Inactive item ----

    @Test
    void inactiveItem_cannotBeOrdered_andNothingIsReserved() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, false);
        long ordersBefore = orderEntityRepository.count();

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("UPI", line(burger, 1))));

        assertEquals(ordersBefore, orderEntityRepository.count());
        assertEquals(0, reload(burger).getReservedQuantity());
    }

    // ---- 6. Multi-item rollback ----

    @Test
    void multiItemCart_whereALaterItemFails_rollsBackTheEarlierItemsReservation() {
        // Item ids sort "a-..." < "b-...", so item A is reserved first, then B fails.
        ItemEntity itemA = anItem("a", "Burger", 10, 0, true);
        ItemEntity itemB = anItem("b", "Fries", 1, 0, true);
        long ordersBefore = orderEntityRepository.count();

        assertThrows(ConflictException.class, () -> orderService.createOrder(
                aRequest("UPI", line(itemB, 5), line(itemA, 2))));

        assertEquals(ordersBefore, orderEntityRepository.count());
        assertEquals(0, reload(itemA).getReservedQuantity(), "item A's reservation must be rolled back");
        assertEquals(10, reload(itemA).getStockQuantity());
        assertEquals(0, reload(itemB).getReservedQuantity());
    }

    @Test
    void multiItemCashCart_whereALaterItemFails_rollsBackTheEarlierItemsCommittedStock() {
        ItemEntity itemA = anItem("a", "Burger", 10, 0, true);
        ItemEntity itemB = anItem("b", "Fries", 1, 0, true);

        assertThrows(ConflictException.class, () -> orderService.createOrder(
                aRequest("CASH", line(itemA, 2), line(itemB, 5))));

        assertEquals(10, reload(itemA).getStockQuantity(), "item A's committed stock must be rolled back");
        assertEquals(0, reload(itemA).getReservedQuantity());
    }

    // ---- 7. Duplicate item ids ----

    @Test
    void duplicateItemIds_areCommittedOnceForTheAggregateQuantity() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, true);

        OrderResponse response = orderService.createOrder(
                aRequest("CASH", line(burger, 2), line(burger, 3)));

        assertEquals(1, response.getItems().size());
        assertEquals(5, response.getItems().get(0).getQuantity());
        assertEquals(50.0, response.getSubtotal(), 0.0001);
        ItemEntity after = reload(burger);
        assertEquals(5, after.getStockQuantity());
        assertEquals(0, after.getReservedQuantity());
    }

    @Test
    void duplicateItemIds_whoseAggregateExceedsStock_areRejectedAsAWhole() {
        // Each line alone (3) fits in stock 5; together (6) they don't.
        ItemEntity burger = anItem("burger", "Burger", 5, 0, true);

        assertThrows(ConflictException.class, () -> orderService.createOrder(
                aRequest("UPI", line(burger, 3), line(burger, 3))));

        assertEquals(0, reload(burger).getReservedQuantity());
    }

    // ---- 10. Lazy expiry of stale PENDING_PAYMENT reservations ----

    @Test
    void staleReservation_isReleased_whileARecentReservationIsKept() {
        ItemEntity burger = anItem("burger", "Burger", 10, 3, true); // 3 held by the stale order
        ItemEntity fries = anItem("fries", "Fries", 10, 2, true);    // 2 held by the recent order
        ItemEntity cola = anItem("cola", "Cola", 10, 4, true);       // 4 held by the stale order, not in the new cart
        OrderEntity stale = aPendingUpiOrder(LocalDateTime.now().minusMinutes(31), true,
                orderLine(burger, 3), orderLine(cola, 4));
        OrderEntity recent = aPendingUpiOrder(LocalDateTime.now().minusMinutes(5), true,
                orderLine(fries, 2));

        orderService.createOrder(aRequest("UPI", line(burger, 1), line(fries, 1)));

        OrderEntity staleAfter = orderEntityRepository.findById(stale.getId()).orElseThrow();
        assertEquals(OrderStatus.PAYMENT_FAILED, staleAfter.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.FAILED, staleAfter.getPaymentDetails().getStatus());
        assertEquals(Boolean.FALSE, staleAfter.getInventoryReserved());
        assertEquals(1, reload(burger).getReservedQuantity(), "stale 3 released, new 1 reserved");
        assertEquals(0, reload(cola).getReservedQuantity(), "every line of the expired order is released");

        OrderEntity recentAfter = orderEntityRepository.findById(recent.getId()).orElseThrow();
        assertEquals(OrderStatus.PENDING_PAYMENT, recentAfter.getOrderStatus());
        assertEquals(Boolean.TRUE, recentAfter.getInventoryReserved());
        assertEquals(3, reload(fries).getReservedQuantity(), "recent 2 kept, new 1 reserved");
    }

    @Test
    void staleReservation_isReleasedOnlyOnce_acrossRepeatedOrders() {
        ItemEntity burger = anItem("burger", "Burger", 10, 3, true);
        aPendingUpiOrder(LocalDateTime.now().minusMinutes(45), true, orderLine(burger, 3));

        orderService.createOrder(aRequest("UPI", line(burger, 1)));
        orderService.createOrder(aRequest("UPI", line(burger, 1)));

        // 3 - 3 (released once) + 1 + 1
        assertEquals(2, reload(burger).getReservedQuantity());
    }

    @Test
    void staleOrdersThatNoLongerAwaitPayment_orNeverReserved_areNotTouched() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, true);
        // Legacy pending order from before inventory tracking: never reserved anything.
        OrderEntity legacy = aPendingUpiOrder(LocalDateTime.now().minusHours(3), null, orderLine(burger, 3));
        // Already paid long ago.
        OrderEntity paid = aPendingUpiOrder(LocalDateTime.now().minusHours(3), false, orderLine(burger, 2));
        paid.setOrderStatus(OrderStatus.PAID);
        orderEntityRepository.save(paid);

        orderService.createOrder(aRequest("UPI", line(burger, 1)));

        assertEquals(1, reload(burger).getReservedQuantity());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderEntityRepository.findById(legacy.getId()).orElseThrow().getOrderStatus());
        assertEquals(OrderStatus.PAID,
                orderEntityRepository.findById(paid.getId()).orElseThrow().getOrderStatus());
    }

    // ---- Admin full-row save vs. a live reservation ----

    @Test
    void adminSaveOfAnItemLoadedBeforeAReservation_isRejected_andTheReservationSurvives() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, true);
        ItemEntity staleAdminCopy = reload(burger); // reservedQuantity = 0 in this copy

        orderService.createOrder(aRequest("UPI", line(burger, 3)));

        staleAdminCopy.setName("Renamed Burger");
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> itemRepository.save(staleAdminCopy));
        ItemEntity after = reload(burger);
        assertEquals(3, after.getReservedQuantity());
        assertEquals("Burger", after.getName());
    }

    // ---- 11. Legacy items ----

    @Test
    void legacyItemWithNoInventoryValues_cannotBeOrdered_andIsLeftUntouched() {
        ItemEntity legacy = anItem("legacy", "Old Item", null, null, null);
        long ordersBefore = orderEntityRepository.count();

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("CASH", line(legacy, 1))));

        assertEquals(ordersBefore, orderEntityRepository.count());
        ItemEntity after = reload(legacy);
        assertNull(after.getStockQuantity());
        assertNull(after.getReservedQuantity());
    }

    @Test
    void backfilledLegacyItemWithZeroStock_isOutOfStock() {
        ItemEntity legacy = anItem("legacy", "Old Item", 0, 0, true);

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("CASH", line(legacy, 1))));
        assertEquals(0, reload(legacy).getStockQuantity());
    }
}
