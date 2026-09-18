package in.vedchangani.billingsoftware.service;

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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Call-level tests for how verifyPayment / failPayment / cancelOrder drive the atomic stock
 * operations: which ones run, in what order, only after which guards, and what a 0-row result
 * does. Real counters and real transaction rollback are covered by
 * OrderPaymentInventoryIntegrationTest against H2.
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentInventoryTest {

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private RazorpayService razorpayService;

    private OrderServiceImpl orderService;
    private UserEntity alice;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
        alice = new UserEntity();
        alice.setId(1L);
        alice.setEmail("alice@example.com");
        alice.setRole("ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice@example.com", null, List.of()));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private OrderItemEntity line(String itemId, int quantity) {
        return OrderItemEntity.builder().itemId(itemId).name(itemId).price(10.0).quantity(quantity).build();
    }

    // A UPI order as Batch 3 leaves it: PENDING_PAYMENT, holding a reservation, with a Razorpay
    // order already attached.
    private OrderEntity aReservedPendingOrder(Boolean inventoryReserved, OrderItemEntity... lines) {
        OrderEntity order = OrderEntity.builder()
                .orderId("ORD1")
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .subtotal(50.0).tax(0.5).grandTotal(50.5)
                .paymentMethod(PaymentMethod.UPI)
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .paymentDetails(PaymentDetails.builder()
                        .status(PaymentDetails.PaymentStatus.PENDING)
                        .razorpayOrderId("rzp_order_1")
                        .build())
                .inventoryReserved(inventoryReserved)
                .items(new ArrayList<>(Arrays.asList(lines)))
                .user(alice)
                .build();
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(order));
        return order;
    }

    private PaymentVerificationRequest aVerification(String razorpayOrderId, String paymentId) {
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId("ORD1");
        request.setRazorpayOrderId(razorpayOrderId);
        request.setRazorpayPaymentId(paymentId);
        request.setRazorpaySignature("sig");
        return request;
    }

    private void signatureIs(boolean valid) {
        when(razorpayService.verifyPaymentSignature(anyString(), anyString(), anyString())).thenReturn(valid);
    }

    private void savesReturnTheirArgument() {
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================== verifyPayment ===========================

    @Test
    void verify_commitsEveryReservedLineInAscendingItemOrder_thenSavesPaid() {
        OrderEntity order = aReservedPendingOrder(true, line("ITEM_B", 3), line("ITEM_A", 2));
        signatureIs(true);
        when(itemRepository.commitReservedStock(anyString(), anyInt())).thenReturn(1);
        savesReturnTheirArgument();

        OrderResponse response = orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1"));

        InOrder inOrder = inOrder(razorpayService, itemRepository, orderEntityRepository);
        inOrder.verify(razorpayService).verifyPaymentSignature("rzp_order_1", "rzp_pay_1", "sig");
        inOrder.verify(itemRepository).commitReservedStock("ITEM_A", 2);
        inOrder.verify(itemRepository).commitReservedStock("ITEM_B", 3);
        inOrder.verify(orderEntityRepository).save(order);

        assertEquals(OrderStatus.PAID, response.getOrderStatus());
        assertEquals("COMPLETED", response.getPaymentStatus());
        assertEquals(Boolean.FALSE, order.getInventoryReserved());
        verify(itemRepository, never()).releaseReservedStock(anyString(), anyInt());
    }

    @Test
    void verify_commitAffectingZeroRows_isConflict_andTheOrderIsNeverMarkedPaid() {
        OrderEntity order = aReservedPendingOrder(true, line("ITEM_A", 2), line("ITEM_B", 3));
        signatureIs(true);
        when(itemRepository.commitReservedStock("ITEM_A", 2)).thenReturn(1);
        when(itemRepository.commitReservedStock("ITEM_B", 3)).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1")));

        assertEquals("Payment could not be completed because inventory could not be finalized.", ex.getMessage());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.PENDING, order.getPaymentDetails().getStatus());
        assertNull(order.getPaymentDetails().getRazorpayPaymentId());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void verify_withInvalidSignature_neverCommitsStock() {
        aReservedPendingOrder(true, line("ITEM_A", 2));
        signatureIs(false);

        assertThrows(IllegalArgumentException.class,
                () -> orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1")));
        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
    }

    @Test
    void verify_withMismatchedRazorpayOrderId_neverCommitsStock() {
        aReservedPendingOrder(true, line("ITEM_A", 2));

        assertThrows(IllegalArgumentException.class,
                () -> orderService.verifyPayment(aVerification("rzp_order_OTHER", "rzp_pay_1")));
        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
    }

    @Test
    void verify_withNoStoredRazorpayOrderId_neverCommitsStock() {
        OrderEntity order = aReservedPendingOrder(true, line("ITEM_A", 2));
        order.getPaymentDetails().setRazorpayOrderId(null);

        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1")));
        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
    }

    @Test
    void verify_replayedAfterPaid_returnsThePaidOrderWithoutCommittingAgain() {
        aReservedPendingOrder(true, line("ITEM_A", 2));
        signatureIs(true);
        when(itemRepository.commitReservedStock("ITEM_A", 2)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1"));
        OrderResponse replay = orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1"));

        assertEquals(OrderStatus.PAID, replay.getOrderStatus());
        verify(itemRepository, times(1)).commitReservedStock(anyString(), anyInt());
        verify(orderEntityRepository, times(1)).save(any());
    }

    @Test
    void verify_ofACancelledOrFailedOrder_neverCommitsStock() {
        OrderEntity order = aReservedPendingOrder(false, line("ITEM_A", 2));
        order.setOrderStatus(OrderStatus.CANCELLED);
        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1")));

        order.setOrderStatus(OrderStatus.PAYMENT_FAILED);
        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1")));

        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
        verify(itemRepository, never()).releaseReservedStock(anyString(), anyInt());
        verify(orderEntityRepository, never()).save(any());
        // Batch 14: the signature IS now evaluated for a terminal order, but only to decide whether a
        // genuine late payment should be logged (see LatePaymentHandlingTest); it never changes
        // the order or stock, which is what this test guards.
    }

    @Test
    void verify_ofALegacyOrderThatNeverReservedStock_isPaidWithoutTouchingInventory() {
        aReservedPendingOrder(null, line("ITEM_A", 2));
        signatureIs(true);
        savesReturnTheirArgument();

        OrderResponse response = orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1"));

        assertEquals(OrderStatus.PAID, response.getOrderStatus());
        verifyNoInteractions(itemRepository);
    }

    // =========================== failPayment ===========================

    @Test
    void failPayment_releasesEveryReservedLineInAscendingItemOrder_thenSavesPaymentFailed() {
        OrderEntity order = aReservedPendingOrder(true, line("ITEM_B", 3), line("ITEM_A", 2));
        when(itemRepository.releaseReservedStock(anyString(), anyInt())).thenReturn(1);
        savesReturnTheirArgument();

        OrderResponse response = orderService.failPayment("ORD1");

        InOrder inOrder = inOrder(itemRepository, orderEntityRepository);
        inOrder.verify(itemRepository).releaseReservedStock("ITEM_A", 2);
        inOrder.verify(itemRepository).releaseReservedStock("ITEM_B", 3);
        inOrder.verify(orderEntityRepository).save(order);
        assertEquals(OrderStatus.PAYMENT_FAILED, response.getOrderStatus());
        assertEquals("FAILED", response.getPaymentStatus());
        assertEquals(Boolean.FALSE, order.getInventoryReserved());
        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
    }

    @Test
    void failPayment_releaseAffectingZeroRows_isConflict_andTheOrderIsNotTransitioned() {
        OrderEntity order = aReservedPendingOrder(true, line("ITEM_A", 2), line("ITEM_B", 3));
        when(itemRepository.releaseReservedStock("ITEM_A", 2)).thenReturn(1);
        when(itemRepository.releaseReservedStock("ITEM_B", 3)).thenReturn(0);

        assertThrows(ConflictException.class, () -> orderService.failPayment("ORD1"));

        assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.PENDING, order.getPaymentDetails().getStatus());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void failPayment_repeated_neverReleasesTwice() {
        aReservedPendingOrder(true, line("ITEM_A", 2));
        when(itemRepository.releaseReservedStock("ITEM_A", 2)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.failPayment("ORD1");
        assertThrows(IllegalStateException.class, () -> orderService.failPayment("ORD1"));

        verify(itemRepository, times(1)).releaseReservedStock(anyString(), anyInt());
    }

    // =========================== cancelOrder ===========================

    @Test
    void cancel_releasesEveryReservedLine_thenSavesCancelled() {
        OrderEntity order = aReservedPendingOrder(true, line("ITEM_A", 2), line("ITEM_B", 3));
        when(itemRepository.releaseReservedStock(anyString(), anyInt())).thenReturn(1);
        savesReturnTheirArgument();

        OrderResponse response = orderService.cancelOrder("ORD1");

        verify(itemRepository).releaseReservedStock("ITEM_A", 2);
        verify(itemRepository).releaseReservedStock("ITEM_B", 3);
        assertEquals(OrderStatus.CANCELLED, response.getOrderStatus());
        assertEquals(Boolean.FALSE, order.getInventoryReserved());
    }

    @Test
    void cancel_releaseAffectingZeroRows_isConflict_andTheOrderIsNotTransitioned() {
        OrderEntity order = aReservedPendingOrder(true, line("ITEM_A", 2));
        when(itemRepository.releaseReservedStock("ITEM_A", 2)).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class, () -> orderService.cancelOrder("ORD1"));

        assertEquals("Payment cancellation could not be completed because inventory could not be released.",
                ex.getMessage());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void cancel_repeated_neverReleasesTwice() {
        aReservedPendingOrder(true, line("ITEM_A", 2));
        when(itemRepository.releaseReservedStock("ITEM_A", 2)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.cancelOrder("ORD1");
        assertThrows(IllegalStateException.class, () -> orderService.cancelOrder("ORD1"));

        verify(itemRepository, times(1)).releaseReservedStock(anyString(), anyInt());
    }

    @Test
    void cancel_ofALegacyOrderThatNeverReservedStock_touchesNoInventory() {
        aReservedPendingOrder(null, line("ITEM_A", 2));
        savesReturnTheirArgument();

        assertEquals(OrderStatus.CANCELLED, orderService.cancelOrder("ORD1").getOrderStatus());
        verifyNoInteractions(itemRepository);
    }

    // =========================== transition races (sequential semantics) ===========================

    @Test
    void verifyThenCancel_cancelIsRejected_andCommittedStockIsNeverReleased() {
        aReservedPendingOrder(true, line("ITEM_A", 2));
        signatureIs(true);
        when(itemRepository.commitReservedStock("ITEM_A", 2)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1"));
        assertThrows(IllegalStateException.class, () -> orderService.cancelOrder("ORD1"));
        assertThrows(IllegalStateException.class, () -> orderService.failPayment("ORD1"));

        verify(itemRepository, never()).releaseReservedStock(anyString(), anyInt());
    }

    @Test
    void cancelThenVerify_verifyIsRejected_andReleasedStockIsNeverCommitted() {
        aReservedPendingOrder(true, line("ITEM_A", 2));
        when(itemRepository.releaseReservedStock("ITEM_A", 2)).thenReturn(1);
        savesReturnTheirArgument();

        orderService.cancelOrder("ORD1");
        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aVerification("rzp_order_1", "rzp_pay_1")));

        verify(itemRepository, never()).commitReservedStock(anyString(), anyInt());
    }
}
