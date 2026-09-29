package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.io.PaymentVerificationRequest;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.OrderServiceImpl;
import in.vedchangani.billingsoftware.service.impl.RazorpayServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PaymentSignatureVerificationTest {

    private static final String TEST_SECRET = "test_secret_key_do_not_use_in_prod";

    private static String signature(String razorpayOrderId, String razorpayPaymentId, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((razorpayOrderId + "|" + razorpayPaymentId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class RealSignatureCheck {

        @Mock
        private OrderEntityRepository orderEntityRepository;

        @Mock
        private UserRepository userRepository;

        private RazorpayServiceImpl razorpayService;

        @BeforeEach
        void setUp() {
            razorpayService = new RazorpayServiceImpl(orderEntityRepository, userRepository);
            ReflectionTestUtils.setField(razorpayService, "razorpayKeySecret", TEST_SECRET);
        }

        @Test
        void acceptsAGenuineSignature() throws Exception {
            String valid = signature("order_ABC123", "pay_XYZ789", TEST_SECRET);

            assertTrue(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", valid));
        }

        @Test
        void rejectsATamperedSignature() throws Exception {
            String valid = signature("order_ABC123", "pay_XYZ789", TEST_SECRET);
            char last = valid.charAt(valid.length() - 1);
            String tampered = valid.substring(0, valid.length() - 1) + (last == 'a' ? 'b' : 'a');

            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", tampered));
        }

        @Test
        void rejectsASignatureMadeWithTheWrongSecret() throws Exception {
            String forged = signature("order_ABC123", "pay_XYZ789", "attacker_guessed_secret");

            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", forged));
        }

        @Test
        void rejectsASignatureBoundToADifferentOrderOrPayment() throws Exception {
            String forOtherOrder = signature("order_OTHER", "pay_XYZ789", TEST_SECRET);
            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", forOtherOrder));

            String forOtherPayment = signature("order_ABC123", "pay_OTHER", TEST_SECRET);
            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", forOtherPayment));
        }

        @Test
        void rejectsGarbageAndMissingValuesInsteadOfThrowing() {
            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", "not-a-signature"));
            assertFalse(razorpayService.verifyPaymentSignature(null, "pay_XYZ789", "sig"));
            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", null, "sig"));
            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", null));
            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", "  "));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class VerifyPaymentGuards {

        @Mock
        private OrderEntityRepository orderEntityRepository;

        @Mock
        private UserRepository userRepository;

        @Mock
        private ItemRepository itemRepository;

        @Mock
        private RazorpayService razorpayService;

        @Mock
        private AuditService auditService;

        private OrderServiceImpl orderService;

        @BeforeEach
        void setUp() {
            orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService, auditService);
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

        private OrderEntity anOrder(UserEntity owner, OrderStatus status, String storedRazorpayOrderId) {
            PaymentDetails pd = PaymentDetails.builder()
                    .status(status == OrderStatus.PAID
                            ? PaymentDetails.PaymentStatus.COMPLETED
                            : PaymentDetails.PaymentStatus.PENDING)
                    .razorpayOrderId(storedRazorpayOrderId)
                    .build();
            return OrderEntity.builder()
                    .orderId("ORD123")
                    .customerName("Walk-in Customer")
                    .phoneNumber("9999999999")
                    .subtotal(new BigDecimal("100.0"))
                    .tax(new BigDecimal("1.0"))
                    .grandTotal(new BigDecimal("101.0"))
                    .paymentMethod(PaymentMethod.UPI)
                    .orderStatus(status)
                    .paymentDetails(pd)
                    .items(List.of())
                    .user(owner)
                    .build();
        }

        private PaymentVerificationRequest aRequest(String razorpayOrderId, String paymentId, String sig) {
            PaymentVerificationRequest request = new PaymentVerificationRequest();
            request.setOrderId("ORD123");
            request.setRazorpayOrderId(razorpayOrderId);
            request.setRazorpayPaymentId(paymentId);
            request.setRazorpaySignature(sig);
            return request;
        }

        @Test
        void validSignatureMarksOrderPaid() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity order = anOrder(alice, OrderStatus.PENDING_PAYMENT, "rzp_order_1");
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(order));
            when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(razorpayService.verifyPaymentSignature("rzp_order_1", "rzp_pay_1", "good_sig")).thenReturn(true);

            OrderResponse result = orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "good_sig"));

            assertEquals(OrderStatus.PAID, result.getOrderStatus());
            assertEquals("COMPLETED", result.getPaymentStatus());
            assertEquals("rzp_pay_1", order.getPaymentDetails().getRazorpayPaymentId());
        }

        @Test
        void invalidSignatureNeverMarksOrderPaid() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity order = anOrder(alice, OrderStatus.PENDING_PAYMENT, "rzp_order_1");
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(order));
            when(razorpayService.verifyPaymentSignature(anyString(), anyString(), anyString())).thenReturn(false);

            assertThrows(RuntimeException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "forged_sig")));

            assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
            assertEquals(PaymentDetails.PaymentStatus.PENDING, order.getPaymentDetails().getStatus());
            assertNull(order.getPaymentDetails().getRazorpayPaymentId());
            verify(orderEntityRepository, never()).save(any());
        }

        @Test
        void mismatchedRazorpayOrderIdIsRejectedBeforeSignatureIsEvenChecked() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity order = anOrder(alice, OrderStatus.PENDING_PAYMENT, "rzp_order_1");
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(order));

            assertThrows(IllegalArgumentException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_SOMEONE_ELSE", "rzp_pay_1", "sig")));

            assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
            verify(razorpayService, never()).verifyPaymentSignature(anyString(), anyString(), anyString());
            verify(orderEntityRepository, never()).save(any());
        }

        @Test
        void otherUsersOrderIsRejected() {
            UserEntity alice = aUser(1L, "alice@example.com");
            UserEntity bob = aUser(2L, "bob@example.com");
            OrderEntity aliceOrder = anOrder(alice, OrderStatus.PENDING_PAYMENT, "rzp_order_1");
            authenticateAs("bob@example.com");

            when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(aliceOrder));

            assertThrows(AccessDeniedException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "sig")));

            assertEquals(OrderStatus.PENDING_PAYMENT, aliceOrder.getOrderStatus());
            verify(razorpayService, never()).verifyPaymentSignature(anyString(), anyString(), anyString());
            verify(orderEntityRepository, never()).save(any());
        }

        @Test
        void repeatedVerificationIsIdempotent() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity order = anOrder(alice, OrderStatus.PENDING_PAYMENT, "rzp_order_1");
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(order));
            when(orderEntityRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(razorpayService.verifyPaymentSignature("rzp_order_1", "rzp_pay_1", "good_sig")).thenReturn(true);

            OrderResponse first = orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "good_sig"));
            OrderResponse second = orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "good_sig"));

            assertEquals(OrderStatus.PAID, first.getOrderStatus());
            assertEquals(OrderStatus.PAID, second.getOrderStatus());
            assertEquals("COMPLETED", second.getPaymentStatus());
            assertEquals("rzp_pay_1", order.getPaymentDetails().getRazorpayPaymentId());
            verify(orderEntityRepository, times(1)).save(any(OrderEntity.class));
        }

        @Test
        void differentPaymentAgainstPaidOrderIsRejected() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity paidOrder = anOrder(alice, OrderStatus.PAID, "rzp_order_1");
            paidOrder.getPaymentDetails().setRazorpayPaymentId("rzp_pay_1");
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(paidOrder));

            assertThrows(IllegalStateException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_DIFFERENT", "sig")));

            assertEquals("rzp_pay_1", paidOrder.getPaymentDetails().getRazorpayPaymentId());
            verify(orderEntityRepository, never()).save(any());
        }

        @Test
        void cancelledOrderCannotBecomePaid() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity cancelled = anOrder(alice, OrderStatus.CANCELLED, "rzp_order_1");
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(cancelled));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "sig")));
            assertTrue(ex.getMessage().contains("CANCELLED"));

            assertEquals(OrderStatus.CANCELLED, cancelled.getOrderStatus());
            verify(orderEntityRepository, never()).save(any());
        }

        @Test
        void paymentFailedOrderCannotBecomePaid() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity failed = anOrder(alice, OrderStatus.PAYMENT_FAILED, "rzp_order_1");
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(failed));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "sig")));
            assertTrue(ex.getMessage().contains("PAYMENT_FAILED"));

            assertEquals(OrderStatus.PAYMENT_FAILED, failed.getOrderStatus());
            verify(orderEntityRepository, never()).save(any());
        }

        @Test
        void orderWithNoRazorpayOrderIdIsRejected() {
            UserEntity alice = aUser(1L, "alice@example.com");
            OrderEntity order = anOrder(alice, OrderStatus.PENDING_PAYMENT, null);
            authenticateAs("alice@example.com");

            when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.of(order));

            assertThrows(IllegalStateException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "sig")));

            verify(razorpayService, never()).verifyPaymentSignature(anyString(), anyString(), anyString());
            verify(orderEntityRepository, never()).save(any());
        }

        @Test
        void nonexistentOrderIsRejected() {
            authenticateAs("alice@example.com");
            when(orderEntityRepository.findByOrderIdForUpdate("ORD123")).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class,
                    () -> orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "sig")));

            verify(orderEntityRepository, never()).save(any());
        }
    }
}
