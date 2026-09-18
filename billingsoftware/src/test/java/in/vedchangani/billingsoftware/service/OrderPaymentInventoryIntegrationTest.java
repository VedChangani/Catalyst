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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end payment-lifecycle inventory behavior against the H2 "test" datasource, through the
 * real transactional OrderService proxy and the REAL Razorpay signature check (a genuine
 * HMAC-SHA256 signature is computed with the test profile's key secret - nothing is mocked).
 *
 * Notation in assertions: S = stock, R = units reserved by *other* orders, Q = this order's units.
 *
 * Deliberately NOT @Transactional, so every assertion reads what really committed or rolled back.
 * H2 runs these sequentially: they prove the guards and rollback semantics, not MySQL InnoDB
 * behavior under truly concurrent requests.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderPaymentInventoryIntegrationTest {

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

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    private UserEntity user;
    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        user = userRepository.save(UserEntity.builder()
                .userId("pay-it-" + suffix)
                .email("payment-it-" + suffix + "@example.com")
                .password("not-used")
                .role("ROLE_USER")
                .name("Payment IT")
                .build());
        category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("pay-it-cat-" + suffix)
                .name("Payment IT " + suffix)
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

    private ItemEntity anItem(String itemIdPrefix, int stock, int reservedByOthers) {
        return itemRepository.save(ItemEntity.builder()
                .itemId(itemIdPrefix + "-" + UUID.randomUUID())
                .name("Item " + itemIdPrefix)
                .price(BigDecimal.valueOf(10))
                .category(category)
                .stockQuantity(stock)
                .reservedQuantity(reservedByOthers)
                .lowStockThreshold(5)
                .active(true)
                .build());
    }

    private ItemEntity reload(ItemEntity item) {
        return itemRepository.findByItemId(item.getItemId()).orElseThrow();
    }

    private void assertStock(ItemEntity item, int expectedStock, int expectedReserved) {
        ItemEntity current = reload(item);
        assertEquals(expectedStock, current.getStockQuantity(), "stockQuantity of " + item.getName());
        assertEquals(expectedReserved, current.getReservedQuantity(), "reservedQuantity of " + item.getName());
    }

    private OrderRequest.OrderItemRequest line(ItemEntity item, int quantity) {
        return new OrderRequest.OrderItemRequest(item.getItemId(), quantity);
    }

    // Creates a UPI order through the real service (so it genuinely reserves stock) and attaches
    // a Razorpay order id to it, standing in for RazorpayServiceImpl.createOrder, which would
    // otherwise call the Razorpay API.
    private OrderEntity aPendingUpiOrderWithRazorpayOrder(String razorpayOrderId, OrderRequest.OrderItemRequest... lines) {
        OrderResponse created = orderService.createOrder(OrderRequest.builder()
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .paymentMethod("UPI")
                .cartItems(Arrays.asList(lines))
                .build());
        OrderEntity order = findOrder(created.getOrderId());
        // Unique, collision-proof id (the generated one is millisecond-based).
        order.setOrderId("PAY-IT-" + UUID.randomUUID());
        if (razorpayOrderId != null) {
            order.getPaymentDetails().setRazorpayOrderId(razorpayOrderId);
        }
        return orderEntityRepository.save(order);
    }

    private OrderEntity findOrder(String orderId) {
        return orderEntityRepository.findAll().stream()
                .filter(o -> o.getOrderId().equals(orderId))
                .findFirst()
                .orElseThrow();
    }

    private OrderEntity reload(OrderEntity order) {
        return orderEntityRepository.findById(order.getId()).orElseThrow();
    }

    private String genuineSignature(String razorpayOrderId, String razorpayPaymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(razorpayKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((razorpayOrderId + "|" + razorpayPaymentId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private PaymentVerificationRequest aVerification(OrderEntity order, String razorpayOrderId,
                                                     String paymentId, String signature) {
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId(order.getOrderId());
        request.setRazorpayOrderId(razorpayOrderId);
        request.setRazorpayPaymentId(paymentId);
        request.setRazorpaySignature(signature);
        return request;
    }

    private PaymentVerificationRequest aGenuineVerification(OrderEntity order, String paymentId) throws Exception {
        String razorpayOrderId = order.getPaymentDetails().getRazorpayOrderId();
        return aVerification(order, razorpayOrderId, paymentId, genuineSignature(razorpayOrderId, paymentId));
    }

    private void assertStillPendingAndReserved(OrderEntity order) {
        OrderEntity current = reload(order);
        assertEquals(OrderStatus.PENDING_PAYMENT, current.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.PENDING, current.getPaymentDetails().getStatus());
        assertNull(current.getPaymentDetails().getRazorpayPaymentId());
        assertEquals(Boolean.TRUE, current.getInventoryReserved());
    }

    // ================= 1. Successful verification: S-Q stock, R reserved =================

    @Test
    void verify_withGenuineSignature_commitsTheReservation_andMarksPaid() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);                         // S=10, R=2
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));
        assertStock(burger, 10, 5);                                          // UPI create: S, R+Q

        OrderResponse response = orderService.verifyPayment(aGenuineVerification(order, "pay_1"));

        assertEquals(OrderStatus.PAID, response.getOrderStatus());
        assertEquals("COMPLETED", response.getPaymentStatus());
        assertStock(burger, 7, 2);                                           // verified: S-Q, R
        OrderEntity after = reload(order);
        assertEquals(OrderStatus.PAID, after.getOrderStatus());
        assertEquals("pay_1", after.getPaymentDetails().getRazorpayPaymentId());
        assertEquals(Boolean.FALSE, after.getInventoryReserved());
    }

    // ================= 2-4. Rejected verifications leave everything untouched =================

    @Test
    void verify_withInvalidSignature_leavesStockAndOrderUnchanged() {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));

        assertThrows(IllegalArgumentException.class, () -> orderService.verifyPayment(
                aVerification(order, "rzp_ok", "pay_1", "forged-signature")));

        assertStock(burger, 10, 5);
        assertStillPendingAndReserved(order);
    }

    @Test
    void verify_withMismatchedRazorpayOrderId_leavesStockAndOrderUnchanged() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));
        // Genuinely signed - but for a different Razorpay order.
        String otherSignature = genuineSignature("rzp_other", "pay_1");

        assertThrows(IllegalArgumentException.class, () -> orderService.verifyPayment(
                aVerification(order, "rzp_other", "pay_1", otherSignature)));

        assertStock(burger, 10, 5);
        assertStillPendingAndReserved(order);
    }

    @Test
    void verify_withNoStoredRazorpayOrderId_leavesStockAndOrderUnchanged() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder(null, line(burger, 3));

        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                aVerification(order, "rzp_ok", "pay_1", genuineSignature("rzp_ok", "pay_1"))));

        assertStock(burger, 10, 5);
        assertStillPendingAndReserved(order);
    }

    // ================= 5-6. Repeated / replayed verification =================

    @Test
    void verify_repeatedWithTheSamePayment_isIdempotent_andNeverDeductsTwice() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));
        PaymentVerificationRequest verification = aGenuineVerification(order, "pay_1");

        orderService.verifyPayment(verification);
        OrderResponse replay = orderService.verifyPayment(verification);

        assertEquals(OrderStatus.PAID, replay.getOrderStatus());
        assertStock(burger, 7, 2);
    }

    @Test
    void verify_ofADifferentPaymentAfterPaid_isRejected_andNeverDeductsTwice() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));
        orderService.verifyPayment(aGenuineVerification(order, "pay_1"));

        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aGenuineVerification(order, "pay_2")));

        assertStock(burger, 7, 2);
        assertEquals("pay_1", reload(order).getPaymentDetails().getRazorpayPaymentId());
    }

    // ================= 7-8. Payment failure: S stock, R reserved =================

    @Test
    void failPayment_releasesTheReservation_andIsNotRepeatable() {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));

        OrderResponse response = orderService.failPayment(order.getOrderId());

        assertEquals(OrderStatus.PAYMENT_FAILED, response.getOrderStatus());
        assertStock(burger, 10, 2);
        assertEquals(Boolean.FALSE, reload(order).getInventoryReserved());

        assertThrows(IllegalStateException.class, () -> orderService.failPayment(order.getOrderId()));
        assertStock(burger, 10, 2);
    }

    // ================= 9-10. Cancellation: S stock, R reserved =================

    @Test
    void cancel_releasesTheReservation_andIsNotRepeatable() {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));

        OrderResponse response = orderService.cancelOrder(order.getOrderId());

        assertEquals(OrderStatus.CANCELLED, response.getOrderStatus());
        assertStock(burger, 10, 2);

        assertThrows(IllegalStateException.class, () -> orderService.cancelOrder(order.getOrderId()));
        assertStock(burger, 10, 2);
    }

    // ================= 11. Whichever transition commits first wins =================

    @Test
    void verifyFirst_thenCancelOrFail_areRejected_andCommittedStockIsNeverReleased() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));

        orderService.verifyPayment(aGenuineVerification(order, "pay_1"));
        assertThrows(IllegalStateException.class, () -> orderService.cancelOrder(order.getOrderId()));
        assertThrows(IllegalStateException.class, () -> orderService.failPayment(order.getOrderId()));

        assertStock(burger, 7, 2);
        assertEquals(OrderStatus.PAID, reload(order).getOrderStatus());
    }

    @Test
    void cancelFirst_thenVerify_isRejected_andReleasedStockIsNeverCommitted() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));

        orderService.cancelOrder(order.getOrderId());
        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aGenuineVerification(order, "pay_1")));

        assertStock(burger, 10, 2);
        assertEquals(OrderStatus.CANCELLED, reload(order).getOrderStatus());
    }

    @Test
    void failFirst_thenVerify_isRejected_andReleasedStockIsNeverCommitted() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3));

        orderService.failPayment(order.getOrderId());
        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aGenuineVerification(order, "pay_1")));

        assertStock(burger, 10, 2);
        assertEquals(OrderStatus.PAYMENT_FAILED, reload(order).getOrderStatus());
    }

    // ================= 12. Partial commit/release failure rolls everything back =================

    // Item ids sort "a-..." < "b-...": item A is mutated first, then item B fails.
    private ItemEntity[] twoItemsWithItemBsReservationDrifted(OrderEntity[] orderHolder) {
        ItemEntity itemA = anItem("a", 10, 0);
        ItemEntity itemB = anItem("b", 10, 0);
        orderHolder[0] = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(itemA, 2), line(itemB, 3));
        // Simulate counters drifting out of sync: B now holds fewer reserved units than the order.
        ItemEntity drifted = reload(itemB);
        drifted.setReservedQuantity(1);
        itemRepository.save(drifted);
        return new ItemEntity[]{itemA, itemB};
    }

    @Test
    void verify_whenALaterLinesCommitFails_rollsBackTheEarlierCommit_andTheOrderStaysPending() throws Exception {
        OrderEntity[] holder = new OrderEntity[1];
        ItemEntity[] items = twoItemsWithItemBsReservationDrifted(holder);
        OrderEntity order = holder[0];

        ConflictException ex = assertThrows(ConflictException.class,
                () -> orderService.verifyPayment(aGenuineVerification(order, "pay_1")));

        assertEquals("Payment could not be completed because inventory could not be finalized.", ex.getMessage());
        assertStock(items[0], 10, 2);   // A's commit rolled back
        assertStock(items[1], 10, 1);   // B untouched
        assertStillPendingAndReserved(order);
    }

    @Test
    void cancel_whenALaterLinesReleaseFails_rollsBackTheEarlierRelease_andTheOrderStaysPending() {
        OrderEntity[] holder = new OrderEntity[1];
        ItemEntity[] items = twoItemsWithItemBsReservationDrifted(holder);
        OrderEntity order = holder[0];

        assertThrows(ConflictException.class, () -> orderService.cancelOrder(order.getOrderId()));

        assertStock(items[0], 10, 2);   // A's release rolled back
        assertStock(items[1], 10, 1);
        assertStillPendingAndReserved(order);
    }

    @Test
    void failPayment_whenALaterLinesReleaseFails_rollsBackTheEarlierRelease_andTheOrderStaysPending() {
        OrderEntity[] holder = new OrderEntity[1];
        ItemEntity[] items = twoItemsWithItemBsReservationDrifted(holder);
        OrderEntity order = holder[0];

        assertThrows(ConflictException.class, () -> orderService.failPayment(order.getOrderId()));

        assertStock(items[0], 10, 2);
        assertStock(items[1], 10, 1);
        assertStillPendingAndReserved(order);
    }

    // ================= 13-14. Multi-item success / failure =================

    @Test
    void verify_ofAMultiItemOrder_commitsEveryLine() throws Exception {
        ItemEntity burger = anItem("burger", 10, 1);
        ItemEntity fries = anItem("fries", 20, 4);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3), line(fries, 5));
        assertStock(burger, 10, 4);
        assertStock(fries, 20, 9);

        orderService.verifyPayment(aGenuineVerification(order, "pay_1"));

        assertStock(burger, 7, 1);
        assertStock(fries, 15, 4);
    }

    @Test
    void failPayment_ofAMultiItemOrder_releasesEveryLine() {
        ItemEntity burger = anItem("burger", 10, 1);
        ItemEntity fries = anItem("fries", 20, 4);
        OrderEntity order = aPendingUpiOrderWithRazorpayOrder("rzp_ok", line(burger, 3), line(fries, 5));

        orderService.failPayment(order.getOrderId());

        assertStock(burger, 10, 1);
        assertStock(fries, 20, 4);
    }

    // ================= Stale-expiry compatibility =================

    @Test
    void anOrderWhoseReservationWasExpired_cannotThenBeVerified() throws Exception {
        ItemEntity burger = anItem("burger", 10, 0);
        OrderEntity stale = aPendingUpiOrderWithRazorpayOrder("rzp_stale", line(burger, 3));
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(31));
        stale = orderEntityRepository.save(stale);

        // Any new order touching the same item lazily expires the stale reservation.
        orderService.createOrder(OrderRequest.builder()
                .customerName("Next Customer").phoneNumber("9999999999").paymentMethod("UPI")
                .cartItems(List.of(line(burger, 1)))
                .build());
        assertEquals(OrderStatus.PAYMENT_FAILED, reload(stale).getOrderStatus());
        assertStock(burger, 10, 1);

        OrderEntity expired = stale;
        assertThrows(IllegalStateException.class,
                () -> orderService.verifyPayment(aGenuineVerification(expired, "pay_late")));

        assertStock(burger, 10, 1);   // nothing committed for the expired order
        assertEquals(OrderStatus.PAYMENT_FAILED, reload(expired).getOrderStatus());
    }

    // ================= Legacy pending order (never reserved) =================

    @Test
    void aLegacyPendingOrderThatNeverReservedStock_canStillBeVerified_withoutTouchingInventory() throws Exception {
        ItemEntity burger = anItem("burger", 10, 2);
        OrderEntity legacy = orderEntityRepository.save(OrderEntity.builder()
                .customerName("Legacy Customer").phoneNumber("8888888888")
                .subtotal(30.0).tax(0.3).grandTotal(30.3)
                .paymentMethod(PaymentMethod.UPI)
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .paymentDetails(PaymentDetails.builder()
                        .status(PaymentDetails.PaymentStatus.PENDING).razorpayOrderId("rzp_legacy").build())
                .user(user)
                .items(new ArrayList<>(List.of(OrderItemEntity.builder()
                        .itemId(burger.getItemId()).name(burger.getName()).price(10.0).quantity(3).build())))
                .build());
        legacy.setOrderId("LEGACY-" + UUID.randomUUID());
        OrderEntity saved = orderEntityRepository.save(legacy);

        OrderResponse response = orderService.verifyPayment(aGenuineVerification(saved, "pay_legacy"));

        assertEquals(OrderStatus.PAID, response.getOrderStatus());
        assertStock(burger, 10, 2);   // no reservation existed, so nothing is committed
    }
}
