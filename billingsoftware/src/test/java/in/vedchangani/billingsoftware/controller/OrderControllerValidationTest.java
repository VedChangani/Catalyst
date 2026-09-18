package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Verifies that OrderController translates the cart/item validation failures thrown by
 * OrderServiceImpl (empty cart, bad quantity, unknown itemId) into a clean 400 response,
 * matching the pattern already used by ItemController/CategoryController/UserController -
 * rather than letting them fall through as an unhandled 500. Authorization failures are
 * left untouched so normal 403 handling still applies.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerValidationTest {

    @Mock
    private OrderService orderService;

    private OrderController orderController;

    private OrderRequest aRequest() {
        return OrderRequest.builder()
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 1)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();
    }

    @Test
    void createOrder_translatesEmptyCartValidationFailureTo400() {
        orderController = new OrderController(orderService);
        when(orderService.createOrder(any())).thenThrow(new IllegalArgumentException("Cart is empty"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> orderController.createOrder(aRequest()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createOrder_translatesUnknownItemIdTo400() {
        orderController = new OrderController(orderService);
        when(orderService.createOrder(any())).thenThrow(new RuntimeException("Item not found: GHOST"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> orderController.createOrder(aRequest()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createOrder_leavesAccessDeniedExceptionUntouched() {
        orderController = new OrderController(orderService);
        when(orderService.createOrder(any())).thenThrow(new AccessDeniedException("No authenticated user found"));

        assertThrows(AccessDeniedException.class, () -> orderController.createOrder(aRequest()));
    }
}
