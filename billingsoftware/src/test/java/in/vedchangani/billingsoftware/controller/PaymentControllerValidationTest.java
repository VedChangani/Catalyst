package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.PaymentRequest;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.RazorpayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Verifies that PaymentController simply delegates to RazorpayServiceImpl/OrderServiceImpl and
 * lets their exceptions (unknown order, wrong owner, wrong order status) propagate unchanged.
 * Translating those exception types into the right HTTP status (400/403/404/409) is
 * GlobalExceptionHandler's job (see GlobalExceptionHandlerTest), not the controller's.
 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerValidationTest {

    @Mock
    private RazorpayService razorpayService;

    @Mock
    private OrderService orderService;

    private PaymentController paymentController;

    private PaymentRequest aRequest() {
        PaymentRequest request = new PaymentRequest();
        request.setOrderId("ORD1");
        request.setCurrency("INR");
        return request;
    }

    @Test
    void createRazorpayOrder_propagatesUnknownOrderFailure() throws Exception {
        paymentController = new PaymentController(razorpayService, orderService);
        when(razorpayService.createOrder(any(), any())).thenThrow(new RuntimeException("Order not found: ORD1"));

        assertThrows(RuntimeException.class, () -> paymentController.createRazorpayOrder(aRequest()));
    }

    @Test
    void createRazorpayOrder_propagatesWrongOrderStatusFailure() throws Exception {
        paymentController = new PaymentController(razorpayService, orderService);
        when(razorpayService.createOrder(any(), any()))
                .thenThrow(new IllegalStateException("Cannot create a payment for order in status: PAID"));

        assertThrows(IllegalStateException.class, () -> paymentController.createRazorpayOrder(aRequest()));
    }

    @Test
    void createRazorpayOrder_leavesAccessDeniedExceptionUntouched() throws Exception {
        paymentController = new PaymentController(razorpayService, orderService);
        when(razorpayService.createOrder(any(), any()))
                .thenThrow(new AccessDeniedException("You are not authorized to create a payment for this order"));

        assertThrows(AccessDeniedException.class, () -> paymentController.createRazorpayOrder(aRequest()));
    }
}
