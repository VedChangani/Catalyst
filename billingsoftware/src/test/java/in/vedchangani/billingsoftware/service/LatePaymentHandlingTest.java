package in.vedchangani.billingsoftware.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Day 4 / Batch 14: a Razorpay payment that arrives after the local order was already cancelled /
 * failed. The order, its stock and the client-visible response must not change; only a genuine
 * (signature-verified) late payment leaves an ERROR trail for manual reconciliation.
 *
 * Real H2 persistence, real signatures (HMAC with the test secret), real services. The log is
 * captured with a logback ListAppender on OrderServiceImpl's logger (logback ships with Spring
 * Boot; nothing new is added). Deliberately NOT @Transactional.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LatePaymentHandlingTest {

    private static final String MARKER = "LATE_VALID_PAYMENT";

    @Autowired private OrderService orderService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    private UserEntity customer;
    private UserEntity otherCustomer;
    private UserEntity cashier;
    private ItemEntity item;
    private ListAppender<ILoggingEvent> logs;
    private Logger orderServiceLogger;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        customer = aUser("Aaron", "aaron-" + s + "@example.com", "ROLE_USER");
        otherCustomer = aUser("Bella", "bella-" + s + "@example.com", "ROLE_USER");
        cashier = aUser("Casey", "casey-" + s + "@example.com", "ROLE_CASHIER");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("late-cat-" + s).name("Late " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("late-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(100).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());

        logs = new ListAppender<>();
        logs.start();
        orderServiceLogger = (Logger) LoggerFactory.getLogger(OrderServiceImpl.class);
        orderServiceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        orderServiceLogger.detachAppender(logs);
        SecurityContextHolder.clearContext();
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity aUser(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).password("not-used")
                .role(role).name(name).mobile(in.vedchangani.billingsoftware.TestMobiles.next()).build());
    }

    private void authenticateAs(UserEntity actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }

    // A UPI order (1 x item = 1 reserved) already tied to a Razorpay order, as create-order would.
    private String pendingOrderTiedTo(String razorpayOrderId) {
        authenticateAs(customer);
        String orderId = orderService.createOrder(OrderRequest.builder()
                .paymentMethod("UPI")
                .cartItems(List.of(new OrderRequest.OrderItemRequest(item.getItemId(), 1))).build()).getOrderId();
        OrderEntity order = stored(orderId);
        order.getPaymentDetails().setRazorpayOrderId(razorpayOrderId);
        orderEntityRepository.save(order);
        return orderId;
    }

    private OrderEntity stored(String orderId) {
        return orderEntityRepository.findByOrderId(orderId).orElseThrow();
    }

    private String sign(String razorpayOrderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(razorpayKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((razorpayOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private PaymentVerificationRequest verification(String orderId, String razorpayOrderId, String paymentId,
                                                    String signature) {
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setOrderId(orderId);
        request.setRazorpayOrderId(razorpayOrderId);
        request.setRazorpayPaymentId(paymentId);
        request.setRazorpaySignature(signature);
        return request;
    }

    private List<ILoggingEvent> lateEvents() {
        return logs.list.stream().filter(e -> e.getFormattedMessage().contains(MARKER)).toList();
    }

    private void assertUntouched(String orderId, OrderStatus status) {
        OrderEntity order = stored(orderId);
        assertEquals(status, order.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.FAILED, order.getPaymentDetails().getStatus());
        assertNull(order.getPaymentDetails().getPaidAt());
        assertNull(order.getPaymentDetails().getRazorpayPaymentId());
        assertNull(order.getPaymentDetails().getRazorpaySignature());
        assertEquals(Boolean.FALSE, order.getInventoryReserved());
        ItemEntity current = itemRepository.findByItemId(item.getItemId()).orElseThrow();
        assertEquals(100, current.getStockQuantity());   // nothing committed
        assertEquals(0, current.getReservedQuantity());  // nothing re-reserved, nothing released twice
    }

    private String cancelled(String razorpayOrderId) {
        String orderId = pendingOrderTiedTo(razorpayOrderId);
        orderService.cancelOrder(orderId);
        return orderId;
    }

    private String failed(String razorpayOrderId) {
        String orderId = pendingOrderTiedTo(razorpayOrderId);
        orderService.failPayment(orderId);
        return orderId;
    }

    // ---- a genuine late payment: rejected, order untouched, ERROR trail ----

    @Test
    void validLatePayment_onCancelledOrder_isRejected_orderUntouched_andLoggedAtError() throws Exception {
        String orderId = cancelled("order_late_c");

        IllegalStateException conflict = assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                verification(orderId, "order_late_c", "pay_late_c", sign("order_late_c", "pay_late_c"))));

        assertEquals("Cannot verify payment for order in status: CANCELLED", conflict.getMessage());
        assertUntouched(orderId, OrderStatus.CANCELLED);
        List<ILoggingEvent> events = lateEvents();
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.get(0).getLevel());
        String message = events.get(0).getFormattedMessage();
        assertTrue(message.contains("orderId=" + orderId), message);
        assertTrue(message.contains("razorpayOrderId=order_late_c"), message);
        assertTrue(message.contains("razorpayPaymentId=pay_late_c"), message);
        assertTrue(message.contains("orderStatus=CANCELLED"), message);
    }

    @Test
    void validLatePayment_onPaymentFailedOrder_isRejected_orderUntouched_andLoggedAtError() throws Exception {
        String orderId = failed("order_late_f");

        IllegalStateException conflict = assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                verification(orderId, "order_late_f", "pay_late_f", sign("order_late_f", "pay_late_f"))));

        assertEquals("Cannot verify payment for order in status: PAYMENT_FAILED", conflict.getMessage());
        assertUntouched(orderId, OrderStatus.PAYMENT_FAILED);
        List<ILoggingEvent> events = lateEvents();
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.get(0).getLevel());
        assertTrue(events.get(0).getFormattedMessage().contains("orderStatus=PAYMENT_FAILED"));
    }

    @Test
    void lateValidPaymentLog_neverContainsTheSignatureOrTheSecret() throws Exception {
        String orderId = cancelled("order_late_s");
        String signature = sign("order_late_s", "pay_late_s");

        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                verification(orderId, "order_late_s", "pay_late_s", signature)));

        assertEquals(1, lateEvents().size());
        for (ILoggingEvent event : logs.list) {
            String rendered = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertFalse(rendered.contains(signature), rendered);
            assertFalse(rendered.contains(razorpayKeySecret), rendered);
        }
    }

    @Test
    void latePayment_repeatedRequests_keepLoggingButNeverChangeAnything() throws Exception {
        String orderId = cancelled("order_late_r");
        PaymentVerificationRequest request = verification(orderId, "order_late_r", "pay_late_r", sign("order_late_r", "pay_late_r"));

        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(request));
        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(request));

        assertEquals(2, lateEvents().size());
        assertUntouched(orderId, OrderStatus.CANCELLED);
    }

    // ---- not genuine / not applicable: no "valid late payment" event ----

    @Test
    void latePayment_withAnInvalidSignature_isRejected_andNeverLoggedAsValid() {
        String cancelledId = cancelled("order_bad_c");
        String failedId = failed("order_bad_f");

        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                verification(cancelledId, "order_bad_c", "pay_x", "0".repeat(64))));
        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                verification(failedId, "order_bad_f", "pay_x", "not-a-signature")));

        assertEquals(0, lateEvents().size());
        assertUntouched(cancelledId, OrderStatus.CANCELLED);
        assertUntouched(failedId, OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void latePayment_signedForADifferentRazorpayOrder_isNotLogged() throws Exception {
        String orderId = cancelled("order_mine");

        // a genuine signature, but for another Razorpay order than the one stored on this order
        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                verification(orderId, "order_someone_else", "pay_y", sign("order_someone_else", "pay_y"))));

        assertEquals(0, lateEvents().size());
        assertUntouched(orderId, OrderStatus.CANCELLED);
    }

    @Test
    void latePayment_bySomeoneWhoDoesNotOwnTheOrder_isDenied_andNotLogged() throws Exception {
        String orderId = cancelled("order_owner");
        authenticateAs(otherCustomer);

        assertThrows(AccessDeniedException.class, () -> orderService.verifyPayment(
                verification(orderId, "order_owner", "pay_z", sign("order_owner", "pay_z"))));

        assertEquals(0, lateEvents().size());
        assertUntouched(orderId, OrderStatus.CANCELLED);
    }

    @Test
    void posOrder_latePayment_isLoggedForItsCreator_notItsCustomer() throws Exception {
        authenticateAs(cashier);
        String orderId = orderService.createPosOrder(PosOrderRequest.builder()
                .customerUserId(customer.getUserId()).paymentMethod("UPI")
                .cartItems(List.of(new OrderRequest.OrderItemRequest(item.getItemId(), 1))).build()).getOrderId();
        OrderEntity order = stored(orderId);
        order.getPaymentDetails().setRazorpayOrderId("order_pos_late");
        orderEntityRepository.save(order);
        orderService.cancelOrder(orderId);
        PaymentVerificationRequest request = verification(orderId, "order_pos_late", "pay_pos", sign("order_pos_late", "pay_pos"));

        authenticateAs(customer); // the associated customer has no payment control over a POS order
        assertThrows(AccessDeniedException.class, () -> orderService.verifyPayment(request));
        assertEquals(0, lateEvents().size());

        authenticateAs(cashier);
        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(request));
        assertEquals(1, lateEvents().size());
    }

    @Test
    void paidOrder_withADifferentPayment_keepsItsExistingConflict_andIsNotALatePayment() throws Exception {
        String orderId = pendingOrderTiedTo("order_paid_x");
        orderService.verifyPayment(verification(orderId, "order_paid_x", "pay_first", sign("order_paid_x", "pay_first")));
        var paidAt = stored(orderId).getPaymentDetails().getPaidAt();
        assertNotNull(paidAt);

        // identical verification: idempotent, no log
        assertEquals(OrderStatus.PAID, orderService.verifyPayment(
                verification(orderId, "order_paid_x", "pay_first", sign("order_paid_x", "pay_first"))).getOrderStatus());
        // a different genuine payment against the paid order: the existing conflict, no late-payment event
        assertThrows(IllegalStateException.class, () -> orderService.verifyPayment(
                verification(orderId, "order_paid_x", "pay_second", sign("order_paid_x", "pay_second"))));

        assertEquals(0, lateEvents().size());
        OrderEntity order = stored(orderId);
        assertEquals(OrderStatus.PAID, order.getOrderStatus());
        assertEquals("pay_first", order.getPaymentDetails().getRazorpayPaymentId());
        assertEquals(paidAt, order.getPaymentDetails().getPaidAt());
        assertEquals(99, itemRepository.findByItemId(item.getItemId()).orElseThrow().getStockQuantity());
    }

    // ---- the client cannot tell a genuine late payment from a forged one ----

    @Test
    void httpResponse_isTheSameSafeConflict_whetherOrNotTheLateSignatureIsValid() throws Exception {
        String orderId = cancelled("order_http");

        MvcResult valid = verifyOverHttp(orderId, "order_http", "pay_h", sign("order_http", "pay_h"));
        MvcResult forged = verifyOverHttp(orderId, "order_http", "pay_h", "0".repeat(64));

        assertEquals(409, valid.getResponse().getStatus());
        assertEquals(409, forged.getResponse().getStatus());
        String validMessage = objectMapper.readTree(valid.getResponse().getContentAsString()).get("message").asText();
        String forgedMessage = objectMapper.readTree(forged.getResponse().getContentAsString()).get("message").asText();
        assertEquals(validMessage, forgedMessage);
        assertEquals("Cannot verify payment for order in status: CANCELLED", validMessage);
        assertEquals(1, lateEvents().size()); // only the genuine one left a trail
        assertUntouched(orderId, OrderStatus.CANCELLED);
    }

    private MvcResult verifyOverHttp(String orderId, String razorpayOrderId, String paymentId, String signature) throws Exception {
        String body = objectMapper.writeValueAsString(verification(orderId, razorpayOrderId, paymentId, signature));
        return mockMvc.perform(post("/payments/verify").with(user(customer.getEmail()).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }
}
