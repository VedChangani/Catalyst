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
import org.springframework.test.util.ReflectionTestUtils;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
                .grandTotal(BigDecimal.valueOf(grandTotal))
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

    @Test
    void createOrder_returnsThePublicKeyId_onFirstAndRepeatCalls_andNeverTheSecret() throws Exception {
        ReflectionTestUtils.setField(razorpayService, "razorpayKeyId", "rzp_test_PUBLICKEY");
        ReflectionTestUtils.setField(razorpayService, "razorpayKeySecret", "TOP-SECRET-VALUE");
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity order = anOrder("ORD1", 32.20, OrderStatus.PENDING_PAYMENT, alice);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(order));
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRazorpayCall("rzp_order_k", 3220L);

        RazorpayOrderResponse first = razorpayService.createOrder("ORD1", "INR");
        RazorpayOrderResponse repeat = razorpayService.createOrder("ORD1", "INR");

        for (RazorpayOrderResponse response : List.of(first, repeat)) {
            assertEquals("rzp_test_PUBLICKEY", response.getKeyId());
            assertEquals("rzp_order_k", response.getId());
            assertEquals(3220, response.getAmount());
            assertEquals("INR", response.getCurrency());
            assertFalse(new ObjectMapper().writeValueAsString(response).contains("TOP-SECRET-VALUE"));
        }
        verify(razorpayService, times(1)).callRazorpayCreateOrder(eq(3220L), eq("INR"));
    }

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
        verify(razorpayService).callRazorpayCreateOrder(eq(20200L), eq("INR"));
        assertEquals("rzp_order_1", order.getPaymentDetails().getRazorpayOrderId());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
    }

    @Test
    void createOrder_ignoresClientSuppliedAmount_andChargesOrderGrandTotal() throws Exception {
        String maliciousBody = "{\"orderId\":\"ORD1\",\"currency\":\"INR\",\"amount\":1}";
        ObjectMapper springBootStyleMapper = Jackson2ObjectMapperBuilder.json().build();
        PaymentRequest request = springBootStyleMapper.readValue(maliciousBody, PaymentRequest.class);

        assertNull(springBootStyleMapper.convertValue(request, java.util.Map.class).get("amount"));

        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        OrderEntity order = anOrder("ORD1", 500.0, OrderStatus.PENDING_PAYMENT, alice);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(orderEntityRepository.findByOrderIdForUpdate("ORD1")).thenReturn(Optional.of(order));
        when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRazorpayCall("rzp_order_500", 50_000L);

        razorpayService.createOrder(request.getOrderId(), request.getCurrency());

        verify(razorpayService).callRazorpayCreateOrder(eq(50_000L), eq("INR"));
        verify(razorpayService, never()).callRazorpayCreateOrder(eq(100L), any());
    }

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
        assertEquals(OrderStatus.PENDING_PAYMENT, savedOrder.getValue().getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.PENDING, savedOrder.getValue().getPaymentDetails().getStatus());
    }

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

    @Test
    void createOrder_rejectsNonExistentOrder() {
        authenticateAs("alice@example.com");
        when(orderEntityRepository.findByOrderIdForUpdate("GHOST")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> razorpayService.createOrder("GHOST", "INR"));
        verify(orderEntityRepository, never()).save(any());
    }

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
        verify(razorpayService, times(1)).callRazorpayCreateOrder(anyLong(), any());
        verify(orderEntityRepository, times(1)).save(any(OrderEntity.class));
        assertEquals("rzp_order_first", order.getPaymentDetails().getRazorpayOrderId());
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

            assertThrows(IllegalStateException.class, () -> razorpayService.createOrder("ORD-T", "INR"));
        }
        verify(orderEntityRepository, never()).save(any());
    }
}
