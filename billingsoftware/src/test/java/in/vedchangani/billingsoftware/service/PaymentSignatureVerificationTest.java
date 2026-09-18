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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for genuine Razorpay payment signature verification.
 *
 * The first nested class exercises the REAL cryptography: it computes an HMAC-SHA256 signature
 * the way Razorpay does and feeds it to RazorpayServiceImpl, with no mocking of the verification
 * itself. That is what proves the old unconditional "return true" is gone - a test that mocked
 * the verifier could not tell the difference.
 *
 * The second nested class covers the surrounding guards in OrderServiceImpl.verifyPayment.
 */
class PaymentSignatureVerificationTest {

    private static final String TEST_SECRET = "test_secret_key_do_not_use_in_prod";

    /**
     * Computes the signature exactly as Razorpay does: HMAC-SHA256 over
     * "<razorpay_order_id>|<razorpay_payment_id>", keyed with the API secret, hex-encoded.
     */
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

    // =====================================================================================
    // Real cryptographic verification - nothing mocked
    // =====================================================================================
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
            // The key secret is injected via @Value in production; set it directly here so the
            // real HMAC path runs. It is never passed as a method argument.
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
            // Flip the last hex character - a one-character forgery must not pass.
            char last = valid.charAt(valid.length() - 1);
            String tampered = valid.substring(0, valid.length() - 1) + (last == 'a' ? 'b' : 'a');

            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", tampered));
        }

        @Test
        void rejectsASignatureMadeWithTheWrongSecret() throws Exception {
            // An attacker who does not hold the server's secret cannot mint a passing signature.
            String forged = signature("order_ABC123", "pay_XYZ789", "attacker_guessed_secret");

            assertFalse(razorpayService.verifyPaymentSignature("order_ABC123", "pay_XYZ789", forged));
        }

        @Test
        void rejectsASignatureBoundToADifferentOrderOrPayment() throws Exception {
            // Correctly signed, but for a different order: replaying it elsewhere must fail.
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

    // =====================================================================================
    // Guards around verification in OrderServiceImpl.verifyPayment
    // =====================================================================================
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
                    .subtotal(100.0)
                    .tax(1.0)
                    .grandTotal(101.0)
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

        // ---- Valid signature settles the order ----
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

        // ---- Invalid signature must NEVER mark the order PAID ----
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

            // The order is left exactly as it was - not PAID, nothing persisted.
            assertEquals(OrderStatus.PENDING_PAYMENT, order.getOrderStatus());
            assertEquals(PaymentDetails.PaymentStatus.PENDING, order.getPaymentDetails().getStatus());
            assertNull(order.getPaymentDetails().getRazorpayPaymentId());
            verify(orderEntityRepository, never()).save(any());
        }

        // ---- Supplied razorpay_order_id must match the one stored against the local order ----
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

        // ---- A user cannot settle another user's order ----
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

        // ---- Repeating the same successful verification is idempotent ----
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
            // Same call again - the order is now PAID and must survive the replay unchanged.
            OrderResponse second = orderService.verifyPayment(aRequest("rzp_order_1", "rzp_pay_1", "good_sig"));

            assertEquals(OrderStatus.PAID, first.getOrderStatus());
            assertEquals(OrderStatus.PAID, second.getOrderStatus());
            assertEquals("COMPLETED", second.getPaymentStatus());
            assertEquals("rzp_pay_1", order.getPaymentDetails().getRazorpayPaymentId());
            // Persisted exactly once: the replay does not write duplicate payment state.
            verify(orderEntityRepository, times(1)).save(any(OrderEntity.class));
        }

        // ---- A different payment replayed against an already-PAID order is still rejected ----
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

        // ---- Invalid order states can never reach PAID ----
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
            // Batch 14: the signature may be evaluated for a diagnostic late-payment log, but a
            // terminal order is never saved, so it can never become PAID (see LatePaymentHandlingTest).
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
            // Batch 14: the signature may be evaluated for a diagnostic late-payment log, but a
            // terminal order is never saved, so it can never become PAID (see LatePaymentHandlingTest).
            verify(orderEntityRepository, never()).save(any());
        }

        // ---- No Razorpay order was ever created for this local order ----
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

        // ---- Nonexistent order ----
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
