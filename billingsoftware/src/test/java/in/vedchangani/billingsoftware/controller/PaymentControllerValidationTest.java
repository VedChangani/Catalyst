package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.PaymentRequest;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.RazorpayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Verifies that PaymentController translates RazorpayServiceImpl's validation failures
 * (unknown order, wrong owner, wrong order status) into a clean 400, the same way
 * OrderController does for order creation - rather than letting them fall through as a 500.
 * Authorization failures are left untouched so normal 403 handling still applies.
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
    void createRazorpayOrder_translatesUnknownOrderTo400() throws Exception {
        paymentController = new PaymentController(razorpayService, orderService);
        when(razorpayService.createOrder(any(), any())).thenThrow(new RuntimeException("Order not found: ORD1"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentController.createRazorpayOrder(aRequest()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createRazorpayOrder_translatesWrongOrderStatusTo400() throws Exception {
        paymentController = new PaymentController(razorpayService, orderService);
        when(razorpayService.createOrder(any(), any()))
                .thenThrow(new IllegalStateException("Cannot create a payment for order in status: PAID"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentController.createRazorpayOrder(aRequest()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createRazorpayOrder_leavesAccessDeniedExceptionUntouched() throws Exception {
        paymentController = new PaymentController(razorpayService, orderService);
        when(razorpayService.createOrder(any(), any()))
                .thenThrow(new AccessDeniedException("You are not authorized to create a payment for this order"));

        assertThrows(AccessDeniedException.class, () -> paymentController.createRazorpayOrder(aRequest()));
    }
}
