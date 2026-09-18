package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Focused tests for order/payment lifecycle:
 *  - Cash order is PAID immediately
 *  - UPI order starts as PENDING_PAYMENT
 *  - Payment verification transitions PENDING_PAYMENT -> PAID
 *  - Invalid transitions (CANCELLED -> PAID, PAYMENT_FAILED -> PAID) are blocked
 *  - failPayment transitions PENDING_PAYMENT -> PAYMENT_FAILED
 *  - cancelOrder transitions PENDING_PAYMENT -> CANCELLED
 *  - Non-owner cannot cancel another user's order
 *  - Cannot cancel a PAID order
 */
@ExtendWith(MockitoExtension.class)
class OrderLifecycleTest {

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
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private UserEntity aUser(Long id, String email) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail(email);
        user.setRole("ROLE_USER");
        return user;
    }

    private ItemEntity anItem(String itemId, String name, double price) {
        return ItemEntity.builder()
                .id(1L)
                .itemId(itemId)
                .name(name)
                .price(BigDecimal.valueOf(price))
                .build();
    }

    private OrderRequest anOrderRequest(String paymentMethod) {
        return OrderRequest.builder()
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 2)))
                .paymentMethod(paymentMethod)
                .build();
    }

    private OrderEntity aPendingUpiOrder(UserEntity owner) {
        PaymentDetails pd = PaymentDetails.builder()
                .status(PaymentDetails.PaymentStatus.PENDING)
                // Recorded by RazorpayServiceImpl.createOrder before checkout opens; payment
                // verification now matches the client's razorpay_order_id against this.
                .razorpayOrderId("rzp_order_1")
                .build();
        return OrderEntity.builder()
                .orderId("ORD123")
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .subtotal(100.0)
                .tax(5.0)
                .grandTotal(105.0)
                .paymentMethod(PaymentMethod.UPI)
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .paymentDetails(pd)
                .items(List.of())
                .user(owner)
                .build();
    }

    // ---- Test 1: Cash order is PAID immediately ----
    @Test
    void createCashOrder_isPaidImmediately() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", "Burger", 50.0)));
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse result = orderService.createOrder(anOrderRequest("CASH"));

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository).save(captor.capture());
        OrderEntity saved = captor.getValue();

        assertEquals(OrderStatus.PAID, saved.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.COMPLETED, saved.getPaymentDetails().getStatus());
        assertEquals(OrderStatus.PAID, result.getOrderStatus());
        assertEquals("COMPLETED", result.getPaymentStatus());
    }

    // ---- Test 2: UPI order starts as PENDING_PAYMENT ----
    @Test
    void createUpiOrder_isPendingPayment() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", "Burger", 50.0)));
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse result = orderService.createOrder(anOrderRequest("UPI"));

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository).save(captor.capture());
        OrderEntity saved = captor.getValue();

        assertEquals(OrderStatus.PENDING_PAYMENT, saved.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.PENDING, saved.getPaymentDetails().getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, result.getOrderStatus());
        assertEquals("PENDING", result.getPaymentStatus());
    }

    // ---- Test 3: Verify payment transitions PENDING_PAYMENT -> PAID ----
    @Test
    void verifyPayment_pendingToPaid() {
        UserEntity alice = aUser(1L, "alice@example.com");
        OrderEntity pendingOrder = aPendingUpiOrder(alice);

        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderId("ORD123")).thenReturn(Optional.of(pendingOrder));
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(razorpayService.verifyPaymentSignature("rzp_order_1", "rzp_pay_1", "sig_1")).thenReturn(true);

        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId("ORD123");
        request.setRazorpayOrderId("rzp_order_1");
        request.setRazorpayPaymentId("rzp_pay_1");
        request.setRazorpaySignature("sig_1");

        OrderResponse result = orderService.verifyPayment(request);

        assertEquals(OrderStatus.PAID, result.getOrderStatus());
        assertEquals("COMPLETED", result.getPaymentStatus());
        assertEquals(OrderStatus.PAID, pendingOrder.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.COMPLETED, pendingOrder.getPaymentDetails().getStatus());
        assertEquals("rzp_order_1", pendingOrder.getPaymentDetails().getRazorpayOrderId());
    }

    // ---- Test 4: Cannot verify payment for a CANCELLED order ----
    @Test
    void verifyPayment_rejectsCancelledOrder() {
        UserEntity alice = aUser(1L, "alice@example.com");
        OrderEntity cancelledOrder = aPendingUpiOrder(alice);
        cancelledOrder.setOrderStatus(OrderStatus.CANCELLED);
        cancelledOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderId("ORD123")).thenReturn(Optional.of(cancelledOrder));

        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId("ORD123");
        request.setRazorpayOrderId("rzp_order_1");
        request.setRazorpayPaymentId("rzp_pay_1");
        request.setRazorpaySignature("sig_1");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(request));
        assertTrue(ex.getMessage().contains("CANCELLED"));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Test 5: Cannot verify payment for a PAYMENT_FAILED order ----
    @Test
    void verifyPayment_rejectsFailedOrder() {
        UserEntity alice = aUser(1L, "alice@example.com");
        OrderEntity failedOrder = aPendingUpiOrder(alice);
        failedOrder.setOrderStatus(OrderStatus.PAYMENT_FAILED);
        failedOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderId("ORD123")).thenReturn(Optional.of(failedOrder));

        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId("ORD123");
        request.setRazorpayOrderId("rzp_order_1");
        request.setRazorpayPaymentId("rzp_pay_1");
        request.setRazorpaySignature("sig_1");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(request));
        assertTrue(ex.getMessage().contains("PAYMENT_FAILED"));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Test 6: failPayment transitions PENDING_PAYMENT -> PAYMENT_FAILED ----
    @Test
    void failPayment_pendingToFailed() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        OrderEntity pendingOrder = aPendingUpiOrder(alice);
        when(orderEntityRepository.findByOrderId("ORD123")).thenReturn(Optional.of(pendingOrder));
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse result = orderService.failPayment("ORD123");

        assertEquals(OrderStatus.PAYMENT_FAILED, result.getOrderStatus());
        assertEquals("FAILED", result.getPaymentStatus());
        assertEquals(OrderStatus.PAYMENT_FAILED, pendingOrder.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.FAILED, pendingOrder.getPaymentDetails().getStatus());
    }

    // ---- Test 7: cancelOrder transitions PENDING_PAYMENT -> CANCELLED ----
    @Test
    void cancelOrder_pendingToCancelled() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        OrderEntity pendingOrder = aPendingUpiOrder(alice);
        when(orderEntityRepository.findByOrderId("ORD123")).thenReturn(Optional.of(pendingOrder));
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse result = orderService.cancelOrder("ORD123");

        assertEquals(OrderStatus.CANCELLED, result.getOrderStatus());
        assertEquals("FAILED", result.getPaymentStatus());
        assertEquals(OrderStatus.CANCELLED, pendingOrder.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.FAILED, pendingOrder.getPaymentDetails().getStatus());
    }

    // ---- Test 8: Non-owner cannot cancel another user's order ----
    @Test
    void cancelOrder_rejectsNonOwner() {
        UserEntity alice = aUser(1L, "alice@example.com");
        UserEntity bob = aUser(2L, "bob@example.com");
        authenticateAs("bob@example.com");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));

        OrderEntity aliceOrder = aPendingUpiOrder(alice);
        when(orderEntityRepository.findByOrderId("ORD123")).thenReturn(Optional.of(aliceOrder));

        assertThrows(AccessDeniedException.class,
                () -> orderService.cancelOrder("ORD123"));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Test 9: Cannot cancel a PAID order ----
    @Test
    void cancelOrder_rejectsPaidOrder() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        OrderEntity paidOrder = aPendingUpiOrder(alice);
        paidOrder.setOrderStatus(OrderStatus.PAID);
        paidOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.COMPLETED);
        when(orderEntityRepository.findByOrderId("ORD123")).thenReturn(Optional.of(paidOrder));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.cancelOrder("ORD123"));
        assertTrue(ex.getMessage().contains("PAID"));
        verify(orderEntityRepository, never()).save(any());
    }
}
