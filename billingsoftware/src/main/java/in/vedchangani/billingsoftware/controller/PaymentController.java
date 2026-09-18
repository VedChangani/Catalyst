package in.vedchangani.billingsoftware.controller;

import com.razorpay.RazorpayException;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PaymentRequest;
import in.vedchangani.billingsoftware.io.PaymentVerificationRequest;
import in.vedchangani.billingsoftware.io.RazorpayOrderResponse;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.RazorpayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final RazorpayService razorpayService;
    private final OrderService orderService;

    // Amount is resolved server-side from the local order's grandTotal (see
    // RazorpayServiceImpl) - the client only identifies which local order this payment is for.
    // Validation failures (unknown order, wrong owner, wrong order status) are translated to a
    // clean response rather than leaking as a 500, consistent with OrderController.
    @PostMapping("/create-order")
    @ResponseStatus(HttpStatus.CREATED)
    public RazorpayOrderResponse createRazorpayOrder(@RequestBody PaymentRequest request) throws RazorpayException {
        try {
            return razorpayService.createOrder(request.getOrderId(), request.getCurrency());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AccessDeniedException ex) {
            // Not a validation failure - let normal auth handling apply (403).
            throw ex;
        } catch (RuntimeException ex) {
            // Thrown by RazorpayServiceImpl when the orderId doesn't match a local order.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    // Verification failures are translated the same way create-order failures are. Note that a
    // bad signature surfaces as a plain 400 with a generic message: nothing about the secret,
    // the expected signature, or which specific check failed is returned to the caller.
    @PostMapping("/verify")
    public OrderResponse verifyPayment(@RequestBody PaymentVerificationRequest request) {
        try {
            return orderService.verifyPayment(request);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
