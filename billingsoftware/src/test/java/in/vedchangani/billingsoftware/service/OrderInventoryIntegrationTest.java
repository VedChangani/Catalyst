package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.TestMoney;
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
                .name("Inventory IT").mobile(in.vedchangani.billingsoftware.TestMobiles.next())
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

    private OrderEntity aPendingUpiOrder(LocalDateTime createdAt, Boolean inventoryReserved, OrderItemEntity... lines) {
        OrderEntity order = orderEntityRepository.save(OrderEntity.builder()
                .customerName("Earlier Customer")
                .phoneNumber("8888888888")
                .subtotal(new BigDecimal("10.0")).tax(new BigDecimal("0.1")).grandTotal(new BigDecimal("10.1"))
                .paymentMethod(PaymentMethod.UPI)
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .paymentDetails(PaymentDetails.builder().status(PaymentDetails.PaymentStatus.PENDING).build())
                .inventoryReserved(inventoryReserved)
                .user(user)
                .items(new ArrayList<>(Arrays.asList(lines)))
                .build());
        order.setCreatedAt(createdAt);
        order.setOrderId("SEED-" + UUID.randomUUID());
        return orderEntityRepository.save(order);
    }

    private OrderItemEntity orderLine(ItemEntity item, int quantity) {
        return OrderItemEntity.builder()
                .itemId(item.getItemId()).name(item.getName()).price(new BigDecimal("10.0")).quantity(quantity)
                .build();
    }

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

    @Test
    void upiOrder_withInsufficientAvailableStock_isConflict_andLeavesNoReservation() {
        ItemEntity burger = anItem("burger", "Burger", 5, 4, true);
        long ordersBefore = orderEntityRepository.count();

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("UPI", line(burger, 2))));

        assertEquals(ordersBefore, orderEntityRepository.count());
        ItemEntity after = reload(burger);
        assertEquals(5, after.getStockQuantity());
        assertEquals(4, after.getReservedQuantity());
    }

    @Test
    void inactiveItem_cannotBeOrdered_andNothingIsReserved() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, false);
        long ordersBefore = orderEntityRepository.count();

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("UPI", line(burger, 1))));

        assertEquals(ordersBefore, orderEntityRepository.count());
        assertEquals(0, reload(burger).getReservedQuantity());
    }

    @Test
    void multiItemCart_whereALaterItemFails_rollsBackTheEarlierItemsReservation() {
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

    @Test
    void duplicateItemIds_areCommittedOnceForTheAggregateQuantity() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, true);

        OrderResponse response = orderService.createOrder(
                aRequest("CASH", line(burger, 2), line(burger, 3)));

        assertEquals(1, response.getItems().size());
        assertEquals(5, response.getItems().get(0).getQuantity());
        TestMoney.assertMoney("50.0", response.getSubtotal());
        ItemEntity after = reload(burger);
        assertEquals(5, after.getStockQuantity());
        assertEquals(0, after.getReservedQuantity());
    }

    @Test
    void duplicateItemIds_whoseAggregateExceedsStock_areRejectedAsAWhole() {
        ItemEntity burger = anItem("burger", "Burger", 5, 0, true);

        assertThrows(ConflictException.class, () -> orderService.createOrder(
                aRequest("UPI", line(burger, 3), line(burger, 3))));

        assertEquals(0, reload(burger).getReservedQuantity());
    }

    @Test
    void staleReservation_isReleased_whileARecentReservationIsKept() {
        ItemEntity burger = anItem("burger", "Burger", 10, 3, true);
        ItemEntity fries = anItem("fries", "Fries", 10, 2, true);
        ItemEntity cola = anItem("cola", "Cola", 10, 4, true);
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

        assertEquals(2, reload(burger).getReservedQuantity());
    }

    @Test
    void staleOrdersThatNoLongerAwaitPayment_orNeverReserved_areNotTouched() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, true);
        OrderEntity legacy = aPendingUpiOrder(LocalDateTime.now().minusHours(3), null, orderLine(burger, 3));
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

    @Test
    void adminSaveOfAnItemLoadedBeforeAReservation_isRejected_andTheReservationSurvives() {
        ItemEntity burger = anItem("burger", "Burger", 10, 0, true);
        ItemEntity staleAdminCopy = reload(burger);

        orderService.createOrder(aRequest("UPI", line(burger, 3)));

        staleAdminCopy.setName("Renamed Burger");
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> itemRepository.save(staleAdminCopy));
        ItemEntity after = reload(burger);
        assertEquals(3, after.getReservedQuantity());
        assertEquals("Burger", after.getName());
    }

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
