package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.OrderCreationResult;
import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Verifies that OrderController simply delegates to OrderServiceImpl and lets its exceptions
 * (empty cart, bad quantity, unknown itemId, ownership failures) propagate unchanged. Translating
 * those exception types into the right HTTP status (400/403/404/409) is GlobalExceptionHandler's
 * job (see GlobalExceptionHandlerTest), not the controller's.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerValidationTest {

    @Mock
    private OrderService orderService;

    private OrderController orderController;

    private OrderRequest aRequest() {
        return OrderRequest.builder()
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 1)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();
    }

    @Test
    void createOrder_propagatesEmptyCartValidationFailure() {
        orderController = new OrderController(orderService);
        when(orderService.createOrder(any(), any())).thenThrow(new IllegalArgumentException("Cart is empty"));

        assertThrows(IllegalArgumentException.class, () -> orderController.createOrder(aRequest(), null));
    }

    @Test
    void createOrder_propagatesAccessDeniedException() {
        orderController = new OrderController(orderService);
        when(orderService.createOrder(any(), any()))
                .thenThrow(new AccessDeniedException("No authenticated user found"));

        assertThrows(AccessDeniedException.class, () -> orderController.createOrder(aRequest(), null));
    }

    @Test
    void createOrder_delegatesToService() {
        orderController = new OrderController(orderService);
        when(orderService.createOrder(any(), any()))
                .thenReturn(new OrderCreationResult(OrderResponse.builder().orderId("ORD1").build(), false));

        orderController.createOrder(aRequest(), null);

        verify(orderService).createOrder(any(), isNull());
    }
}
