package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // USER + ADMIN: place a new order (see SecurityConfig)
    // Cart/item validation failures (empty cart, bad quantity, unknown itemId) are client
    // input errors, so they are translated to a clean 400 rather than leaking as a 500 -
    // consistent with how ItemController/CategoryController/UserController handle their
    // own validation failures.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        try {
            return orderService.createOrder(request);
        } catch (IllegalArgumentException ex) {
            // Empty cart, missing/invalid quantity, etc.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (AccessDeniedException ex) {
            // Not a validation failure - let normal auth handling apply (403).
            throw ex;
        } catch (RuntimeException ex) {
            // Thrown by OrderServiceImpl when an itemId in the cart doesn't exist in the catalog.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    // ADMIN-only administrative operation (see SecurityConfig)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{orderId}")
    public void deleteOrder(@PathVariable String orderId) {
        orderService.deleteOrder(orderId);
    }

    // ADMIN-only: all orders placed in the system (see SecurityConfig)
    @GetMapping("/latest")
    public List<OrderResponse> getLatestOrders() {
        return orderService.getLatestOrders();
    }

    // USER + ADMIN: the currently authenticated user's own orders (see SecurityConfig).
    @GetMapping("/my-orders")
    public List<OrderResponse> getMyOrders() {
        return orderService.getMyOrders();
    }

    // USER + ADMIN: cancel a PENDING_PAYMENT order (ownership enforced in service layer)
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancelOrder(@PathVariable String orderId) {
        return orderService.cancelOrder(orderId);
    }

    // USER + ADMIN: mark a PENDING_PAYMENT order as PAYMENT_FAILED (ownership enforced in service layer)
    @PostMapping("/{orderId}/fail-payment")
    public OrderResponse failPayment(@PathVariable String orderId) {
        return orderService.failPayment(orderId);
    }
}
