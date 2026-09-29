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

    static final String PAYMENT_CURRENCY = "INR";

    @Override
    @Transactional
    public RazorpayOrderResponse createOrder(String localOrderId, String ignoredClientCurrency) throws RazorpayException {
        OrderEntity localOrder = resolveOrderForPayment(localOrderId);

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

        PaymentDetails paymentDetails = localOrder.getPaymentDetails();
        if (paymentDetails == null) {
            paymentDetails = PaymentDetails.builder()
                    .status(PaymentDetails.PaymentStatus.PENDING)
                    .build();
            localOrder.setPaymentDetails(paymentDetails);
        }
        paymentDetails.setRazorpayOrderId(response.getId());
        orderEntityRepository.save(localOrder);

        response.setKeyId(razorpayKeyId);
        return response;
    }

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

    private UserEntity getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("No authenticated user found");
        }
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found: " + email));
    }

    @Override
    public boolean verifyPaymentSignature(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
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
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

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
