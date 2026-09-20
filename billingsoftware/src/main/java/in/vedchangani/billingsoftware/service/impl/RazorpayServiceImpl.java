package in.vedchangani.billingsoftware.service.impl;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.RazorpayOrderResponse;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.RazorpayService;
import in.vedchangani.billingsoftware.util.Money;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RazorpayServiceImpl implements RazorpayService {

    @Value("${razorpay.key.id}")
    private String razorpayKeyId;
    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    private final OrderEntityRepository orderEntityRepository;
    private final UserRepository userRepository;

    // Every payment is in Indian rupees. The currency is deliberately NOT client-controlled: the
    // "currency" a caller may still send (PaymentRequest) is accepted for backward compatibility
    // and ignored.
    static final String PAYMENT_CURRENCY = "INR";

    // Holds the local order's row lock (see resolveOrderForPayment) until the Razorpay order id is
    // saved. Without it, this method's full-row save could land after a concurrent cancel/fail and
    // silently flip the order - and its stock-reservation flag - back to PENDING_PAYMENT. The lock
    // is held across the Razorpay API call; only concurrent transitions of this one order wait.
    //
    // Idempotent per local order: the first call creates exactly one Razorpay order and stores its
    // id; every later call (double click, retry, refresh) returns that same provider order rebuilt
    // from local state, without calling Razorpay and without touching the stored id. Replacing it
    // would orphan the original order - a payment made against it could then never be verified.
    // The row lock makes two concurrent first calls queue: the second one sees the stored id.
    @Override
    @Transactional
    public RazorpayOrderResponse createOrder(String localOrderId, String ignoredClientCurrency) throws RazorpayException {
        // Resolves and validates the local order; a client-supplied amount is never consulted -
        // the payment amount always comes from this order's authoritative grandTotal.
        OrderEntity localOrder = resolveOrderForPayment(localOrderId);

        // Razorpay expects the amount in the smallest currency sub-unit (paise for INR). It is the
        // order's authoritative BigDecimal grand total converted exactly (Money.toMinorUnits: rounded
        // HALF_UP to paise, then moved two places) - no floating-point step.
        long amountInPaise = Money.toMinorUnits(localOrder.getGrandTotal());

        PaymentDetails existingDetails = localOrder.getPaymentDetails();
        if (existingDetails != null && existingDetails.getRazorpayOrderId() != null) {
            return RazorpayOrderResponse.builder()
                    .keyId(razorpayKeyId)
                    .id(existingDetails.getRazorpayOrderId())
                    .entity("order")
                    .amount(Math.toIntExact(amountInPaise))
                    .currency(PAYMENT_CURRENCY)
                    .status("created")
                    .build();
        }

        RazorpayOrderResponse response = callRazorpayCreateOrder(amountInPaise, PAYMENT_CURRENCY);

        // Record the Razorpay order ID against the local order so it can be matched up during
        // verification. This does NOT change the local order's status - it stays
        // PENDING_PAYMENT until verifyPayment (with signature verification) marks it PAID.
        // paymentDetails is normally populated by OrderServiceImpl.createOrder, but legacy
        // rows (written before the embeddable existed) can read back as null, so initialise
        // it here rather than risking an NPE that would strand an already-created Razorpay order.
        PaymentDetails paymentDetails = localOrder.getPaymentDetails();
        if (paymentDetails == null) {
            paymentDetails = PaymentDetails.builder()
                    .status(PaymentDetails.PaymentStatus.PENDING)
                    .build();
            localOrder.setPaymentDetails(paymentDetails);
        }
        paymentDetails.setRazorpayOrderId(response.getId());
        orderEntityRepository.save(localOrder);

        // The browser opens Checkout with the same PUBLIC key id this order was created with.
        response.setKeyId(razorpayKeyId);
        return response;
    }

    /**
     * Loads the local order by orderId and enforces the invariants required before a Razorpay
     * order can be created for it:
     *  - the order must exist
     *  - it must belong to the currently authenticated user (or, for a POS order, have been
     *    entered by them)
     *  - it must still be PENDING_PAYMENT (not already PAID, CANCELLED, or PAYMENT_FAILED)
     */
    private OrderEntity resolveOrderForPayment(String localOrderId) {
        OrderEntity localOrder = orderEntityRepository.findByOrderIdForUpdate(localOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + localOrderId));

        UserEntity currentUser = getAuthenticatedUser();
        if (!localOrder.canBeManagedBy(currentUser)) {
            throw new AccessDeniedException("You are not authorized to create a payment for this order");
        }

        if (localOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot create a payment for order in status: " + localOrder.getOrderStatus());
        }

        return localOrder;
    }

    /**
     * Resolves the UserEntity for the currently authenticated principal, the same way
     * OrderServiceImpl does: by email, from the JWT-authenticated principal - never from any
     * client-supplied id.
     */
    private UserEntity getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("No authenticated user found");
        }
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found: " + email));
    }

    /**
     * Real signature verification, delegated to the Razorpay SDK's own implementation
     * (com.razorpay.Utils.verifyPaymentSignature, available in razorpay-java 1.4.1). It
     * recomputes HMAC-SHA256 over "razorpay_order_id|razorpay_payment_id" with the key secret
     * and compares it to the supplied signature in constant time.
     *
     * The key secret is read from configuration into this service only - it is never accepted
     * from a caller, never returned, and never written to a log or exception message.
     */
    @Override
    public boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        // A missing field can never produce a valid signature; reject before touching the SDK
        // so a null doesn't surface as an exception that a caller might mistake for a bug.
        if (isBlank(razorpayOrderId) || isBlank(razorpayPaymentId) || isBlank(razorpaySignature)) {
            return false;
        }

        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", razorpayOrderId);
            attributes.put("razorpay_payment_id", razorpayPaymentId);
            attributes.put("razorpay_signature", razorpaySignature);
            return Utils.verifyPaymentSignature(attributes, razorpayKeySecret);
        } catch (RazorpayException ex) {
            // Treat any verification error as a failed verification. Deliberately no logging of
            // the exception payload, the signature, or the secret - a failure here is reported
            // only as a boolean.
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The actual call to the Razorpay API. Kept as its own (package-visible) method so it can
     * be stubbed out in tests without making a real network call, while still exercising the
     * validation/amount-resolution logic above through the real createOrder(...) method.
     */
    RazorpayOrderResponse callRazorpayCreateOrder(long amountInPaise, String currency) throws RazorpayException {
        RazorpayClient razorpayClient = new RazorpayClient(razorpayKeyId, razorpayKeySecret);
        JSONObject orderRequest = new JSONObject();
        orderRequest.put("amount", amountInPaise);
        orderRequest.put("currency", currency);
        orderRequest.put("receipt", "order_rcptid_" + System.currentTimeMillis());
        orderRequest.put("payment_capture", 1);

        Order order = razorpayClient.orders.create(orderRequest);
        return convertToResponse(order);
    }

    private RazorpayOrderResponse convertToResponse(Order order) {
        return RazorpayOrderResponse.builder()
                .id(order.get("id"))
                .entity(order.get("entity"))
                .amount(order.get("amount"))
                .currency(order.get("currency"))
                .status(order.get("status"))
                .created_at(order.get("created_at"))
                .receipt(order.get("receipt"))
                .build();
    }
}
