package in.vedchangani.billingsoftware.controller;

import com.razorpay.RazorpayException;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PaymentRequest;
import in.vedchangani.billingsoftware.io.PaymentVerificationRequest;
import in.vedchangani.billingsoftware.io.RazorpayOrderResponse;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.RazorpayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final RazorpayService razorpayService;
    private final OrderService orderService;

    // Amount is resolved server-side from the local order's grandTotal (see
    // RazorpayServiceImpl) - the client only identifies which local order this payment is for.
    // Validation/business-rule failures (unknown order, wrong owner, wrong order status) are
    // mapped to their HTTP status by GlobalExceptionHandler (400/403/404/409 respectively).
    @PostMapping("/create-order")
    @ResponseStatus(HttpStatus.CREATED)
    public RazorpayOrderResponse createRazorpayOrder(@Valid @RequestBody PaymentRequest request) throws RazorpayException {
        return razorpayService.createOrder(request.getOrderId(), request.getCurrency());
    }

    // A bad signature surfaces as a plain 400/409 with a generic message: nothing about the
    // secret, the expected signature, or which specific check failed is returned to the caller.
    @PostMapping("/verify")
    public OrderResponse verifyPayment(@Valid @RequestBody PaymentVerificationRequest request) {
        return orderService.verifyPayment(request);
    }
}
