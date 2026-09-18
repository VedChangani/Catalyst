package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Call-level tests for the inventory side of createOrder: which atomic repository operations run,
 * in what order, and what happens when one of them affects 0 rows. Real transaction rollback and
 * real stock counters are covered separately by OrderInventoryIntegrationTest against H2.
 */
@ExtendWith(MockitoExtension.class)
class OrderInventoryReservationTest {

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private RazorpayService razorpayService;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice@example.com", null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void aliceIsAuthenticated() {
        UserEntity alice = new UserEntity();
        alice.setId(1L);
        alice.setEmail("alice@example.com");
        alice.setRole("ROLE_USER");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
    }

    private void itemExists(String itemId, String name, double price, Boolean active) {
        when(itemRepository.findByItemId(itemId)).thenReturn(Optional.of(ItemEntity.builder()
                .itemId(itemId).name(name).price(BigDecimal.valueOf(price))
                .active(active).stockQuantity(100).reservedQuantity(0)
                .build()));
    }

    private void savesReturnTheirArgument() {
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderRequest aRequest(String paymentMethod, OrderRequest.OrderItemRequest... lines) {
        return OrderRequest.builder()
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .paymentMethod(paymentMethod)
                .cartItems(Arrays.asList(lines))
                .build();
    }

    private OrderRequest.OrderItemRequest line(String itemId, int quantity) {
        return new OrderRequest.OrderItemRequest(itemId, quantity);
    }

    private OrderEntity savedOrder() {
        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---- CASH: reserve -> commit -> PAID ----

    @Test
    void cashOrder_reservesThenCommits_andIsSavedPaidWithNoOutstandingReservation() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(itemRepository.reserveStock("ITEM1", 2)).thenReturn(1);
        when(itemRepository.commitReservedStock("ITEM1", 2)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.createOrder(aRequest("CASH", line("ITEM1", 2)));

        InOrder inOrder = inOrder(itemRepository, orderEntityRepository);
        inOrder.verify(itemRepository).reserveStock("ITEM1", 2);
        inOrder.verify(itemRepository).commitReservedStock("ITEM1", 2);
        inOrder.verify(orderEntityRepository).save(any(OrderEntity.class));

        OrderEntity saved = savedOrder();
        assertEquals(OrderStatus.PAID, saved.getOrderStatus());
        assertEquals(Boolean.FALSE, saved.getInventoryReserved());
    }

    @Test
    void cashOrder_commitAffectingZeroRows_abortsOrderCreation() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(itemRepository.reserveStock("ITEM1", 2)).thenReturn(1);
        when(itemRepository.commitReservedStock("ITEM1", 2)).thenReturn(0);

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("CASH", line("ITEM1", 2))));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- UPI: reserve only, PENDING_PAYMENT ----

    @Test
    void upiOrder_reservesButNeverCommits_andIsSavedPendingWithReservationFlag() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(itemRepository.reserveStock("ITEM1", 2)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.createOrder(aRequest("UPI", line("ITEM1", 2)));

        verify(itemRepository).reserveStock("ITEM1", 2);
        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
        OrderEntity saved = savedOrder();
        assertEquals(OrderStatus.PENDING_PAYMENT, saved.getOrderStatus());
        assertEquals(Boolean.TRUE, saved.getInventoryReserved());
    }

    // ---- Conflicts ----

    @Test
    void reservationAffectingZeroRows_isConflict_andNothingIsSaved() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(itemRepository.reserveStock("ITEM1", 5)).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("UPI", line("ITEM1", 5))));
        assertTrue(ex.getMessage().startsWith("Insufficient stock or item is unavailable"));
        assertTrue(ex.getMessage().contains("Burger"));
        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void inactiveItem_isRejectedBeforeAnyInventoryIsTouched() {
        itemExists("ITEM1", "Burger", 50.0, false);

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("CASH", line("ITEM1", 1))));
        verify(itemRepository, never()).reserveStock(anyString(), anyInt());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void legacyItemWithNoActiveValue_isTreatedAsUnavailable() {
        itemExists("ITEM1", "Burger", 50.0, null);

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("CASH", line("ITEM1", 1))));
        verify(itemRepository, never()).reserveStock(anyString(), anyInt());
    }

    @Test
    void laterLineFailing_stopsProcessing_andNoOrderIsSaved() {
        aliceIsAuthenticated();
        itemExists("ITEM_A", "Burger", 50.0, true);
        itemExists("ITEM_B", "Fries", 20.0, true);
        itemExists("ITEM_C", "Cola", 10.0, true);
        when(itemRepository.reserveStock("ITEM_A", 1)).thenReturn(1);
        when(itemRepository.reserveStock("ITEM_B", 1)).thenReturn(0);

        assertThrows(ConflictException.class, () -> orderService.createOrder(aRequest("UPI",
                line("ITEM_A", 1), line("ITEM_B", 1), line("ITEM_C", 1))));

        // ITEM_A's reservation is undone by the transaction rollback, not by a manual release.
        verify(itemRepository, never()).releaseReservedStock(anyString(), anyInt());
        verify(itemRepository, never()).reserveStock(eq("ITEM_C"), anyInt());
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Duplicate item ids ----

    @Test
    void duplicateItemIds_areAggregatedIntoOneReservationCommitAndSnapshotLine() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(itemRepository.reserveStock("ITEM1", 5)).thenReturn(1);
        when(itemRepository.commitReservedStock("ITEM1", 5)).thenReturn(1);
        savesReturnTheirArgument();

        OrderResponse response = orderService.createOrder(aRequest("CASH", line("ITEM1", 2), line("ITEM1", 3)));

        verify(itemRepository, times(1)).findByItemId("ITEM1");
        verify(itemRepository, times(1)).reserveStock(anyString(), anyInt());
        verify(itemRepository, times(1)).commitReservedStock(anyString(), anyInt());
        assertEquals(1, response.getItems().size());
        assertEquals(5, response.getItems().get(0).getQuantity());
        assertEquals(250.0, response.getSubtotal(), 0.0001);
    }

    @Test
    void quantitiesThatWouldOverflowWhenAggregated_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(
                aRequest("CASH", line("ITEM1", Integer.MAX_VALUE), line("ITEM1", 1))));
        verifyNoInteractions(itemRepository);
    }

    // ---- Deterministic lock order ----

    @Test
    void inventoryMutations_runInAscendingItemIdOrder_whileSnapshotKeepsCartOrder() {
        aliceIsAuthenticated();
        itemExists("ITEM_C", "Cola", 10.0, true);
        itemExists("ITEM_A", "Burger", 50.0, true);
        itemExists("ITEM_B", "Fries", 20.0, true);
        when(itemRepository.reserveStock(anyString(), anyInt())).thenReturn(1);
        when(itemRepository.commitReservedStock(anyString(), anyInt())).thenReturn(1);
        savesReturnTheirArgument();

        OrderResponse response = orderService.createOrder(aRequest("CASH",
                line("ITEM_C", 1), line("ITEM_A", 1), line("ITEM_B", 1)));

        InOrder inOrder = inOrder(itemRepository);
        inOrder.verify(itemRepository).reserveStock("ITEM_A", 1);
        inOrder.verify(itemRepository).commitReservedStock("ITEM_A", 1);
        inOrder.verify(itemRepository).reserveStock("ITEM_B", 1);
        inOrder.verify(itemRepository).commitReservedStock("ITEM_B", 1);
        inOrder.verify(itemRepository).reserveStock("ITEM_C", 1);
        inOrder.verify(itemRepository).commitReservedStock("ITEM_C", 1);

        assertEquals(List.of("ITEM_C", "ITEM_A", "ITEM_B"),
                response.getItems().stream().map(OrderResponse.OrderItemResponse::getItemId).toList());
    }

    // ---- Lazy expiry of stale reservations ----

    private OrderEntity aStaleReservedOrder(long id, OrderItemEntity... lines) {
        return OrderEntity.builder()
                .id(id)
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .inventoryReserved(true)
                .createdAt(LocalDateTime.now().minusHours(2))
                .items(new ArrayList<>(Arrays.asList(lines)))
                .build();
    }

    private OrderItemEntity orderLine(String itemId, int quantity) {
        return OrderItemEntity.builder().itemId(itemId).name(itemId).price(1.0).quantity(quantity).build();
    }

    @Test
    void staleReservation_isClaimedThenReleased_inTheSameAscendingItemPassAsTheNewReservation() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(orderEntityRepository.findStaleReservedOrdersContainingItems(
                eq(OrderStatus.PENDING_PAYMENT), any(LocalDateTime.class), anyCollection()))
                .thenReturn(List.of(aStaleReservedOrder(7L, orderLine("ITEM9", 2), orderLine("ITEM1", 3))));
        when(orderEntityRepository.claimStaleReservationForExpiry(7L, OrderStatus.PENDING_PAYMENT,
                OrderStatus.PAYMENT_FAILED, PaymentDetails.PaymentStatus.FAILED)).thenReturn(1);
        when(itemRepository.releaseReservedStock(anyString(), anyInt())).thenReturn(1);
        when(itemRepository.reserveStock("ITEM1", 1)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.createOrder(aRequest("UPI", line("ITEM1", 1)));

        InOrder inOrder = inOrder(orderEntityRepository, itemRepository);
        inOrder.verify(orderEntityRepository).claimStaleReservationForExpiry(eq(7L), any(), any(), any());
        inOrder.verify(itemRepository).releaseReservedStock("ITEM1", 3);
        inOrder.verify(itemRepository).reserveStock("ITEM1", 1);
        // Every line of the expired order is released - including ITEM9, which isn't in this cart.
        inOrder.verify(itemRepository).releaseReservedStock("ITEM9", 2);
    }

    @Test
    void staleReservationAlreadyClaimedElsewhere_isNeverReleasedAgain() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(orderEntityRepository.findStaleReservedOrdersContainingItems(
                eq(OrderStatus.PENDING_PAYMENT), any(LocalDateTime.class), anyCollection()))
                .thenReturn(List.of(aStaleReservedOrder(7L, orderLine("ITEM1", 3))));
        when(orderEntityRepository.claimStaleReservationForExpiry(eq(7L), any(), any(), any())).thenReturn(0);
        when(itemRepository.reserveStock("ITEM1", 1)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.createOrder(aRequest("UPI", line("ITEM1", 1)));

        verify(itemRepository, never()).releaseReservedStock(anyString(), anyInt());
    }

    @Test
    void staleReleaseAffectingZeroRows_isConflict_andNothingIsReservedOrSaved() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(orderEntityRepository.findStaleReservedOrdersContainingItems(
                eq(OrderStatus.PENDING_PAYMENT), any(LocalDateTime.class), anyCollection()))
                .thenReturn(List.of(aStaleReservedOrder(7L, orderLine("ITEM1", 3))));
        when(orderEntityRepository.claimStaleReservationForExpiry(eq(7L), any(), any(), any())).thenReturn(1);
        when(itemRepository.releaseReservedStock("ITEM1", 3)).thenReturn(0);

        assertThrows(ConflictException.class,
                () -> orderService.createOrder(aRequest("UPI", line("ITEM1", 1))));
        verify(itemRepository, never()).reserveStock(anyString(), anyInt());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void staleLookupIsCutOffAtTheReservationTimeout() {
        aliceIsAuthenticated();
        itemExists("ITEM1", "Burger", 50.0, true);
        when(itemRepository.reserveStock("ITEM1", 1)).thenReturn(1);
        savesReturnTheirArgument();

        LocalDateTime before = LocalDateTime.now().minusMinutes(30);
        orderService.createOrder(aRequest("UPI", line("ITEM1", 1)));
        LocalDateTime after = LocalDateTime.now().minusMinutes(30);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderEntityRepository).findStaleReservedOrdersContainingItems(
                eq(OrderStatus.PENDING_PAYMENT), cutoff.capture(), eq(java.util.Set.of("ITEM1")));
        assertFalse(cutoff.getValue().isBefore(before));
        assertFalse(cutoff.getValue().isAfter(after));
    }
}
