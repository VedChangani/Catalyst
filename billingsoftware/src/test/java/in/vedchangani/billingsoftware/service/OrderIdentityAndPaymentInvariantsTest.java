package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Day 4 / Batch 12: order identity and payment-identifier invariants, and paidAt, against the H2
 * "test" datasource through the real transactional services. Deliberately NOT @Transactional so
 * that constraint violations and service transactions are real.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderIdentityAndPaymentInvariantsTest {

    private static final String ORDER_ID_PATTERN = "^ORD\\d{13}-[0-9A-F]{8}$";

    @Autowired private OrderService orderService;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    private UserEntity customer;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        customer = userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email("inv-" + s + "@example.com")
                .password("not-used").role("ROLE_USER").name("Invariant Customer").build());
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("inv-cat-" + s).name("Invariants " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("inv-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(1000).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(customer.getEmail(), null, List.of()));
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

    private OrderRequest request(String paymentMethod) {
        return OrderRequest.builder()
                .customerName("Invariant Customer").phoneNumber("9999999999")
                .paymentMethod(paymentMethod)
                .cartItems(List.of(new OrderRequest.OrderItemRequest(item.getItemId(), 1)))
                .build();
    }

    private OrderEntity stored(String orderId) {
        return orderEntityRepository.findByOrderId(orderId).orElseThrow();
    }

    private OrderEntity bareOrder(PaymentDetails paymentDetails) {
        return OrderEntity.builder()
                .customerName("X").phoneNumber("9999999999")
                .subtotal(10.0).tax(0.1).grandTotal(10.1)
                .paymentMethod(PaymentMethod.UPI).orderStatus(OrderStatus.PENDING_PAYMENT)
                .paymentDetails(paymentDetails).inventoryReserved(false).user(customer)
                .build();
    }

    // A pending UPI order that has been tied to the given Razorpay order, as create-order would.
    private String pendingUpiOrderTiedTo(String razorpayOrderId) {
        String orderId = orderService.createOrder(request("UPI")).getOrderId();
        OrderEntity order = stored(orderId);
        order.getPaymentDetails().setRazorpayOrderId(razorpayOrderId);
        orderEntityRepository.save(order);
        return orderId;
    }

    private PaymentVerificationRequest verification(String orderId, String razorpayOrderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(razorpayKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((razorpayOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId(orderId);
        request.setRazorpayOrderId(razorpayOrderId);
        request.setRazorpayPaymentId(paymentId);
        request.setRazorpaySignature(hex.toString());
        return request;
    }

    // ---- order identity ----

    @Test
    void rapidSuccessiveOrders_getDistinctWellFormedOrderIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String orderId = orderService.createOrder(request("CASH")).getOrderId();
            assertTrue(orderId.matches(ORDER_ID_PATTERN), orderId);
            ids.add(orderId);
        }

        assertEquals(50, ids.size());
        assertEquals(50, orderEntityRepository.count());
        ids.forEach(id -> assertTrue(orderEntityRepository.findByOrderId(id).isPresent()));
    }

    @Test
    void database_rejectsDuplicateOrderId() {
        OrderEntity first = orderEntityRepository.save(bareOrder(PaymentDetails.builder()
                .status(PaymentDetails.PaymentStatus.PENDING).build()));
        OrderEntity second = orderEntityRepository.save(bareOrder(PaymentDetails.builder()
                .status(PaymentDetails.PaymentStatus.PENDING).build()));
        assertNotEquals(first.getOrderId(), second.getOrderId());

        second.setOrderId(first.getOrderId());

        assertThrows(DataIntegrityViolationException.class, () -> orderEntityRepository.saveAndFlush(second));
    }

    // ---- provider identifiers ----

    @Test
    void database_rejectsDuplicateRazorpayOrderId() {
        orderEntityRepository.saveAndFlush(bareOrder(PaymentDetails.builder()
                .status(PaymentDetails.PaymentStatus.PENDING).razorpayOrderId("order_dup").build()));

        assertThrows(DataIntegrityViolationException.class, () -> orderEntityRepository.saveAndFlush(
                bareOrder(PaymentDetails.builder()
                        .status(PaymentDetails.PaymentStatus.PENDING).razorpayOrderId("order_dup").build())));
    }

    @Test
    void database_rejectsDuplicateRazorpayPaymentId() {
        orderEntityRepository.saveAndFlush(bareOrder(PaymentDetails.builder()
                .status(PaymentDetails.PaymentStatus.COMPLETED)
                .razorpayOrderId("order_a").razorpayPaymentId("pay_dup").build()));

        assertThrows(DataIntegrityViolationException.class, () -> orderEntityRepository.saveAndFlush(
                bareOrder(PaymentDetails.builder()
                        .status(PaymentDetails.PaymentStatus.COMPLETED)
                        .razorpayOrderId("order_b").razorpayPaymentId("pay_dup").build())));
    }

    @Test
    void nullProviderIdentifiers_areAllowedOnManyOrders() {
        // CASH orders never have Razorpay identifiers
        orderService.createOrder(request("CASH"));
        orderService.createOrder(request("CASH"));
        orderService.createOrder(request("UPI"));
        orderService.createOrder(request("UPI"));

        assertEquals(4, orderEntityRepository.count());
        orderEntityRepository.findAll().forEach(order -> {
            assertNull(order.getPaymentDetails().getRazorpayOrderId());
            assertNull(order.getPaymentDetails().getRazorpayPaymentId());
        });
    }

    // ---- paidAt ----

    @Test
    void paidAt_isSetOnSuccessfulVerification_andPreservedOnIdempotentReplay() throws Exception {
        String orderId = pendingUpiOrderTiedTo("order_paid_" + UUID.randomUUID());
        String razorpayOrderId = stored(orderId).getPaymentDetails().getRazorpayOrderId();
        assertNull(stored(orderId).getPaymentDetails().getPaidAt());

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        OrderResponse paid = orderService.verifyPayment(verification(orderId, razorpayOrderId, "pay_ok_1"));

        LocalDateTime paidAt = stored(orderId).getPaymentDetails().getPaidAt();
        assertEquals(OrderStatus.PAID, paid.getOrderStatus());
        assertNotNull(paidAt);
        assertFalse(paidAt.isBefore(before));
        assertEquals(paidAt, paid.getPaymentDetails().getPaidAt());

        OrderResponse replay = orderService.verifyPayment(verification(orderId, razorpayOrderId, "pay_ok_1"));

        assertEquals(OrderStatus.PAID, replay.getOrderStatus());
        assertEquals(paidAt, stored(orderId).getPaymentDetails().getPaidAt());
        assertEquals(paidAt, replay.getPaymentDetails().getPaidAt());
    }

    @Test
    void paidAt_staysNullForCancelledFailedAndCashOrders() {
        String cancelled = orderService.createOrder(request("UPI")).getOrderId();
        String failed = orderService.createOrder(request("UPI")).getOrderId();
        String cash = orderService.createOrder(request("CASH")).getOrderId();

        OrderResponse cancelResponse = orderService.cancelOrder(cancelled);
        OrderResponse failResponse = orderService.failPayment(failed);

        assertEquals(OrderStatus.CANCELLED, stored(cancelled).getOrderStatus());
        assertEquals(OrderStatus.PAYMENT_FAILED, stored(failed).getOrderStatus());
        assertEquals(OrderStatus.PAID, stored(cash).getOrderStatus());
        assertNull(stored(cancelled).getPaymentDetails().getPaidAt());
        assertNull(stored(failed).getPaymentDetails().getPaidAt());
        assertNull(stored(cash).getPaymentDetails().getPaidAt());
        assertNull(cancelResponse.getPaymentDetails().getPaidAt());
        assertNull(failResponse.getPaymentDetails().getPaidAt());
    }

    @Test
    void failedVerification_doesNotSetPaidAt() throws Exception {
        String orderId = pendingUpiOrderTiedTo("order_bad_" + UUID.randomUUID());
        PaymentVerificationRequest forged = verification(orderId,
                stored(orderId).getPaymentDetails().getRazorpayOrderId(), "pay_x");
        forged.setRazorpaySignature("0".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> orderService.verifyPayment(forged));

        assertEquals(OrderStatus.PENDING_PAYMENT, stored(orderId).getOrderStatus());
        assertNull(stored(orderId).getPaymentDetails().getPaidAt());
    }
}
