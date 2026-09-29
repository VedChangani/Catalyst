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

    @PostMapping("/create-order")
    @ResponseStatus(HttpStatus.CREATED)
    public RazorpayOrderResponse createRazorpayOrder(@Valid @RequestBody PaymentRequest request) throws RazorpayException {
        return razorpayService.createOrder(request.getOrderId(), request.getCurrency());
    }

    @PostMapping("/verify")
    public OrderResponse verifyPayment(@Valid @RequestBody PaymentVerificationRequest request) {
        return orderService.verifyPayment(request);
    }
}
