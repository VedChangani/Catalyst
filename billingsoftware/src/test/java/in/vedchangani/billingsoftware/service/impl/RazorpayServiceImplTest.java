package in.vedchangani.billingsoftware.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.PaymentRequest;
import in.vedchangani.billingsoftware.io.RazorpayOrderResponse;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for RazorpayServiceImpl.createOrder:
 *  - the Razorpay order amount always comes from the local order's grandTotal, never from
 *    any client-supplied value (there is no amount parameter to tamper with any more)
 *  - a user cannot create a payment for another user's order
 *  - a nonexistent orderId is rejected
 *  - an order that isn't PENDING_PAYMENT (PAID/CANCELLED/PAYMENT_FAILED) is rejected
 * The actual network call to Razorpay is stubbed via callRazorpayCreateOrder so these tests
 * exercise the real validation/amount-resolution logic without hitting the Razorpay API.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayServiceImplTest {

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private UserRepository userRepository;

    private RazorpayServiceImpl razorpayService;

    @BeforeEach
    void setUp() {
        razorpayService = spy(new RazorpayServiceImpl(orderEntityRepository, userRepository));
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

    private OrderEntity anOrder(String orderId, double grandTotal, OrderStatus status, UserEntity owner) {
        OrderEntity order = OrderEntity.builder()
                .orderId(orderId)
                .grandTotal(grandTotal)
                .orderStatus(status)
                .user(owner)
                .paymentDetails(PaymentDetails.builder().status(PaymentDetails.PaymentStatus.PENDING).build())
                .build();
        return order;
    }

    private void stubRazorpayCall(String returnedOrderId, long expectedAmountInPaise) throws Exception {
        RazorpayOrderResponse fakeResponse = RazorpayOrderResponse.builder()
                .id(returnedOrderId)
                .entity("order")
                .amount((int) expectedAmountInPaise)
                .currency("INR")
                .status("created")
                .build();
        doReturn(fakeResponse).when(razorpayService).callRazorpayCreateOrder(anyLong(), any());
    }

    // ---- Amount always comes from the local order's grandTotal; there is nothing for a client to tamper with ----
    @Test
    void createOrder_usesBackendGrandTotal_convertedToPaise() throws Exception {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity order = anOrder("ORD1", 202.0, OrderStatus.PENDING_PAYMENT, alice);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(order));
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRazorpayCall("rzp_order_1", 20200L);

        RazorpayOrderResponse result = razorpayService.createOrder("ORD1", "INR");

        assertEquals("rzp_order_1", result.getId());
        // 202.0 rupees -> 20200 paise, straight from grandTotal - never from a client value.
        // (eq(long) below is Mockito's primitive overload, so no boxing/ArgumentCaptor pitfalls.)
        verify(razorpayService).callRazorpayCreateOrder(eq(20200L), eq("INR"));
        assertEquals("rzp_order_1", order.getPaymentDetails().getRazorpayOrderId());
        // Status is untouched - verification (with signature check) is what moves it to PAID.
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
    }

    /**
     * End-to-end proof of the requirement's worked example: the local order's grandTotal is
     * Rs.500 while the client's JSON body claims an amount of Rs.1. The request is deserialized
     * with a Jackson mapper configured the way Spring Boot configures the one backing request
     * body binding, so this genuinely exercises the step where a rogue "amount" field would
     * have to survive in order to do damage. The Razorpay order must still be Rs.500.
     */
    @Test
    void createOrder_ignoresClientSuppliedAmount_andChargesOrderGrandTotal() throws Exception {
        String maliciousBody = "{\"orderId\":\"ORD1\",\"currency\":\"INR\",\"amount\":1}";
        ObjectMapper springBootStyleMapper = Jackson2ObjectMapperBuilder.json().build();
        PaymentRequest request = springBootStyleMapper.readValue(maliciousBody, PaymentRequest.class);

        // The DTO has no amount property at all, so the client's value is dropped at binding
        // time - it never even reaches the service.
        assertNull(springBootStyleMapper.convertValue(request, java.util.Map.class).get("amount"));

        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity order = anOrder("ORD1", 500.0, OrderStatus.PENDING_PAYMENT, alice);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(order));
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRazorpayCall("rzp_order_500", 50_000L);

        razorpayService.createOrder(request.getOrderId(), request.getCurrency());

        // Rs.500 grandTotal -> 50000 paise. NOT the 100 paise the client asked to be charged.
        verify(razorpayService).callRazorpayCreateOrder(eq(50_000L), eq("INR"));
        verify(razorpayService, never()).callRazorpayCreateOrder(eq(100L), any());
    }

    /**
     * The generated Razorpay order id must be persisted against the order it was created for,
     * so the later verification step has something trustworthy to compare against. Asserting on
     * the entity actually handed to save(...) - rather than on the local fixture - is what makes
     * this a real check that the right row is being updated.
     */
    @Test
    void createOrder_persistsRazorpayOrderIdAgainstTheCorrectLocalOrder() throws Exception {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity order = anOrder("ORD-CORRECT", 250.0, OrderStatus.PENDING_PAYMENT, alice);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD-CORRECT")).thenReturn(Optional.of(order));
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRazorpayCall("rzp_order_correct", 25_000L);

        razorpayService.createOrder("ORD-CORRECT", "INR");

        ArgumentCaptor<OrderEntity> savedOrder = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository).save(savedOrder.capture());
        assertEquals("ORD-CORRECT", savedOrder.getValue().getOrderId());
        assertEquals("rzp_order_correct", savedOrder.getValue().getPaymentDetails().getRazorpayOrderId());
        // Creating the Razorpay order does not by itself settle the payment.
        assertEquals(OrderStatus.PENDING_PAYMENT, savedOrder.getValue().getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.PENDING, savedOrder.getValue().getPaymentDetails().getStatus());
    }

    /**
     * Legacy orders can read back with a null paymentDetails embeddable. The Razorpay order id
     * still has to land somewhere, so the service initialises it rather than throwing an NPE
     * after the remote order has already been created.
     */
    @Test
    void createOrder_initialisesPaymentDetailsWhenMissing() throws Exception {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity legacyOrder = anOrder("ORD-LEGACY", 100.0, OrderStatus.PENDING_PAYMENT, alice);
        legacyOrder.setPaymentDetails(null);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD-LEGACY")).thenReturn(Optional.of(legacyOrder));
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRazorpayCall("rzp_order_legacy", 10_000L);

        razorpayService.createOrder("ORD-LEGACY", "INR");

        assertNotNull(legacyOrder.getPaymentDetails());
        assertEquals("rzp_order_legacy", legacyOrder.getPaymentDetails().getRazorpayOrderId());
    }

    // ---- A user cannot create a payment for another user's order ----
    @Test
    void createOrder_rejectsOrderBelongingToAnotherUser() throws Exception {
        UserEntity alice = aUser(1L, "alice@example.com");
        UserEntity bob = aUser(2L, "bob@example.com");
        authenticateAs("bob@example.com");
        OrderEntity aliceOrder = anOrder("ORD1", 100.0, OrderStatus.PENDING_PAYMENT, alice);

        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(aliceOrder));

        assertThrows(AccessDeniedException.class, () -> razorpayService.createOrder("ORD1", "INR"));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Nonexistent order id is rejected ----
    @Test
    void createOrder_rejectsNonExistentOrder() {
        authenticateAs("alice@example.com");
        when(orderEntityRepository.findByOrderIdForUpdate("GHOST")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> razorpayService.createOrder("GHOST", "INR"));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Only PENDING_PAYMENT orders can create a Razorpay order ----
    @Test
    void createOrder_rejectsAlreadyPaidOrder() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity paidOrder = anOrder("ORD1", 100.0, OrderStatus.PAID, alice);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(paidOrder));

        assertThrows(IllegalStateException.class, () -> razorpayService.createOrder("ORD1", "INR"));
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void createOrder_rejectsCancelledOrder() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity cancelledOrder = anOrder("ORD1", 100.0, OrderStatus.CANCELLED, alice);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(cancelledOrder));

        assertThrows(IllegalStateException.class, () -> razorpayService.createOrder("ORD1", "INR"));
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void createOrder_rejectsPaymentFailedOrder() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity failedOrder = anOrder("ORD1", 100.0, OrderStatus.PAYMENT_FAILED, alice);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(failedOrder));

        assertThrows(IllegalStateException.class, () -> razorpayService.createOrder("ORD1", "INR"));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Batch 14: idempotent create-order, server-controlled currency ----

    @Test
    void createOrder_calledTwice_callsRazorpayOnce_andKeepsTheStoredRazorpayOrderId() throws Exception {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity order = anOrder("ORD1", 202.0, OrderStatus.PENDING_PAYMENT, alice);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(order));
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRazorpayCall("rzp_order_first", 20_200L);

        RazorpayOrderResponse first = razorpayService.createOrder("ORD1", "INR");
        RazorpayOrderResponse second = razorpayService.createOrder("ORD1", "INR");

        assertEquals("rzp_order_first", first.getId());
        assertEquals("rzp_order_first", second.getId());
        // exactly one provider order was created, and the stored id was written once and kept
        verify(razorpayService, times(1)).callRazorpayCreateOrder(anyLong(), any());
        verify(orderEntityRepository, times(1)).save(any(OrderEntity.class));
        assertEquals("rzp_order_first", order.getPaymentDetails().getRazorpayOrderId());
        // the repeat is rebuilt from the local order: same amount (grandTotal x 100), INR
        assertEquals(20_200, second.getAmount());
        assertEquals("INR", second.getCurrency());
        assertEquals("created", second.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
    }

    @Test
    void createOrder_neverOverwritesAnAlreadyStoredRazorpayOrderId() throws Exception {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity order = anOrder("ORD1", 100.0, OrderStatus.PENDING_PAYMENT, alice);
        order.getPaymentDetails().setRazorpayOrderId("rzp_original");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(order));

        RazorpayOrderResponse response = razorpayService.createOrder("ORD1", "INR");

        assertEquals("rzp_original", response.getId());
        assertEquals(10_000, response.getAmount());
        verify(razorpayService, never()).callRazorpayCreateOrder(anyLong(), any());
        verify(orderEntityRepository, never()).save(any());
        assertEquals("rzp_original", order.getPaymentDetails().getRazorpayOrderId());
    }

    @Test
    void createOrder_alwaysCreatesTheProviderOrderInInr_whateverCurrencyTheClientSends() throws Exception {
        for (String clientCurrency : new String[]{"INR", "USD", "usd", "EUR", "", null, "not-a-currency"}) {
            razorpayService = spy(new RazorpayServiceImpl(orderEntityRepository, userRepository));
            UserEntity alice = aUser(1L, "alice@example.com");
            authenticateAs("alice@example.com");
            OrderEntity order = anOrder("ORD-CUR", 75.5, OrderStatus.PENDING_PAYMENT, alice);
            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD-CUR")).thenReturn(Optional.of(order));
            when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            stubRazorpayCall("rzp_cur", 7_550L);

            razorpayService.createOrder("ORD-CUR", clientCurrency);

            // amount = persisted grandTotal x 100 (Rs.75.50 -> 7550 paise); currency is always INR
            verify(razorpayService).callRazorpayCreateOrder(eq(7_550L), eq("INR"));
            verify(razorpayService, never()).callRazorpayCreateOrder(anyLong(), argThat(c -> !"INR".equals(c)));
        }
    }

    @Test
    void createOrder_repeatOnATerminalOrderStillCreatesNothing() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        for (OrderStatus terminal : new OrderStatus[]{OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.PAYMENT_FAILED}) {
            OrderEntity order = anOrder("ORD-T", 100.0, terminal, alice);
            order.getPaymentDetails().setRazorpayOrderId("rzp_existing");
            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD-T")).thenReturn(Optional.of(order));

            // even with a stored provider id, a non-pending order gets neither a new nor the old one
            assertThrows(IllegalStateException.class, () -> razorpayService.createOrder("ORD-T", "INR"));
        }
        verify(orderEntityRepository, never()).save(any());
    }
}
