package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The UPI (Razorpay) payment lifecycle end to end, with the exact inventory numbers of the manual
 * test: an item with stock 5 / reserved 0 and a UPI order of 1. Real security chain, endpoints,
 * services, H2 and genuine HMAC signatures (test profile key secret) - only the network call that
 * creates the provider order is not made; the Razorpay order id is stored as create-order would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UpiPaymentLifecycleTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;

    @Value("${razorpay.key.secret}")
    private String keySecret;

    private UserEntity customer;
    private UserEntity otherCustomer;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        customer = account();
        otherCustomer = account();
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("upi-cat-" + s).name("UPI " + s).build());
        item = itemRepository.save(ItemEntity.builder().itemId("upi-item-" + s).name("Phone case")
                .price(new BigDecimal("499.00")).category(category)
                .stockQuantity(5).reservedQuantity(0).lowStockThreshold(1).active(true).build());
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- helpers ----

    private UserEntity account() {
        return userRepository.save(UserEntity.builder().userId("uid-" + UUID.randomUUID())
                .email("upi-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .name("Upi Customer").role("ROLE_USER").mobile(TestMobiles.next()).password("not-used").build());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserEntity actor) {
        return request.with(user(actor.getEmail()).roles("USER"));
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private void assertStock(int stock, int reserved) {
        ItemEntity now = itemRepository.findByItemId(item.getItemId()).orElseThrow();
        assertEquals(stock, now.getStockQuantity(), "stockQuantity");
        assertEquals(reserved, now.getReservedQuantity(), "reservedQuantity");
    }

    private OrderEntity stored(String orderId) {
        return orderEntityRepository.findByOrderId(orderId).orElseThrow();
    }

    // Places a UPI order of 1 and ties it to a Razorpay order id (what POST /payments/create-order
    // stores). Checks the reservation: stock stays 5, 1 is reserved.
    private String placeUpiOrder(String razorpayOrderId) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/orders"), customer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"UPI\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}"))
                .andReturn();
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode order = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("PENDING_PAYMENT", order.get("orderStatus").asText());
        assertStock(5, 1);
        String orderId = order.get("orderId").asText();
        OrderEntity entity = stored(orderId);
        entity.getPaymentDetails().setRazorpayOrderId(razorpayOrderId);
        orderEntityRepository.save(entity);
        return orderId;
    }

    private String signature(String razorpayOrderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((razorpayOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }

    private MockHttpServletRequestBuilder verify(UserEntity actor, String orderId, String rzpOrder, String payment, String sig) {
        return as(post("/payments/verify"), actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\",\"razorpayOrderId\":\"" + rzpOrder
                        + "\",\"razorpayPaymentId\":\"" + payment + "\",\"razorpaySignature\":\"" + sig + "\"}");
    }

    // ---- failure ----

    @Test
    void paymentFailure_marksPaymentFailed_releasesTheReservationOnce_andKeepsTheOrder() throws Exception {
        String orderId = placeUpiOrder("order_fail_1");

        assertEquals(200, status(as(post("/orders/" + orderId + "/fail-payment"), customer)));
        OrderEntity failed = stored(orderId);
        assertEquals(OrderStatus.PAYMENT_FAILED, failed.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.FAILED, failed.getPaymentDetails().getStatus());
        assertNull(failed.getPaymentDetails().getPaidAt());
        assertStock(5, 0);

        // repeated / conflicting settlements are rejected and never release twice
        assertEquals(409, status(as(post("/orders/" + orderId + "/fail-payment"), customer)));
        assertEquals(409, status(as(post("/orders/" + orderId + "/cancel"), customer)));
        assertStock(5, 0);
        // and a genuine payment arriving afterwards cannot revive it
        assertEquals(409, status(verify(customer, orderId, "order_fail_1", "pay_late", signature("order_fail_1", "pay_late"))));
        assertEquals(OrderStatus.PAYMENT_FAILED, stored(orderId).getOrderStatus());
        assertStock(5, 0);
        // the order still exists and is in the customer's history
        assertTrue(mockMvc.perform(as(get("/orders/my-orders"), customer)).andReturn().getResponse()
                .getContentAsString().contains(orderId));
    }

    // ---- cancellation ----

    @Test
    void cancellation_marksCancelled_releasesTheReservationOnce_andKeepsTheOrder() throws Exception {
        String orderId = placeUpiOrder("order_cancel_1");

        assertEquals(200, status(as(post("/orders/" + orderId + "/cancel"), customer)));
        assertEquals(OrderStatus.CANCELLED, stored(orderId).getOrderStatus());
        assertStock(5, 0);

        assertEquals(409, status(as(post("/orders/" + orderId + "/cancel"), customer)));
        assertEquals(409, status(as(post("/orders/" + orderId + "/fail-payment"), customer)));
        assertStock(5, 0);
        assertTrue(orderEntityRepository.findByOrderId(orderId).isPresent());
    }

    // ---- success ----

    @Test
    void verifiedPayment_marksPaid_setsPaidAt_andCommitsTheStock_onlyWithAGenuineSignature() throws Exception {
        String orderId = placeUpiOrder("order_ok_1");

        // a forged signature (e.g. a browser claiming success) changes nothing
        assertEquals(400, status(verify(customer, orderId, "order_ok_1", "pay_1", "0000forged")));
        assertEquals(OrderStatus.PENDING_PAYMENT, stored(orderId).getOrderStatus());
        assertStock(5, 1);
        // a genuine signature for a DIFFERENT Razorpay order changes nothing
        assertEquals(400, status(verify(customer, orderId, "order_other", "pay_1", signature("order_other", "pay_1"))));
        assertStock(5, 1);
        // someone else cannot settle this customer's order
        assertEquals(403, status(verify(otherCustomer, orderId, "order_ok_1", "pay_1", signature("order_ok_1", "pay_1"))));
        assertStock(5, 1);

        MvcResult ok = mockMvc.perform(verify(customer, orderId, "order_ok_1", "pay_1", signature("order_ok_1", "pay_1"))).andReturn();
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());
        assertEquals("PAID", objectMapper.readTree(ok.getResponse().getContentAsString()).get("orderStatus").asText());
        OrderEntity paid = stored(orderId);
        assertEquals(OrderStatus.PAID, paid.getOrderStatus());
        assertEquals(PaymentDetails.PaymentStatus.COMPLETED, paid.getPaymentDetails().getStatus());
        assertNotNull(paid.getPaymentDetails().getPaidAt());
        assertEquals("pay_1", paid.getPaymentDetails().getRazorpayPaymentId());
        assertStock(4, 0);

        // a replayed verification is idempotent: still PAID, stock committed exactly once
        assertEquals(200, status(verify(customer, orderId, "order_ok_1", "pay_1", signature("order_ok_1", "pay_1"))));
        assertStock(4, 0);
        // a paid order can no longer be cancelled or failed
        assertEquals(409, status(as(post("/orders/" + orderId + "/cancel"), customer)));
        assertEquals(409, status(as(post("/orders/" + orderId + "/fail-payment"), customer)));
        assertStock(4, 0);
    }
}
