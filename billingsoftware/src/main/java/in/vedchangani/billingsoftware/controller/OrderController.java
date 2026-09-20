package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.OrderCreationResult;
import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // USER only: place a new ONLINE order (see SecurityConfig; staff use POST /pos/orders). DTO validation (blank customer
    // name, invalid phone, empty cart, invalid quantity) is enforced by @Valid; business-rule
    // failures (unknown itemId, invalid payment method) and their HTTP status are handled by
    // OrderServiceImpl + GlobalExceptionHandler.
    // Optional Idempotency-Key header: a retry of the same checkout attempt returns the existing
    // order with 200 instead of creating another one (201 = newly created).
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        OrderCreationResult result = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.status(result.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.getOrder());
    }

    // ADMIN-only: all orders placed in the system (see SecurityConfig)
    @GetMapping("/latest")
    public List<OrderResponse> getLatestOrders() {
        return orderService.getLatestOrders();
    }

    // USER only: the authenticated customer's own orders (see SecurityConfig). Admins use
    // GET /admin/orders; cashiers use GET /pos/sales.
    @GetMapping("/my-orders")
    public List<OrderResponse> getMyOrders() {
        return orderService.getMyOrders();
    }

    // USER: one order from the authenticated customer's own history, ONLINE or linked POS.
    // CASHIER: a POS sale the cashier entered (createdBy + POS). Ownership is enforced in the
    // service layer (see SecurityConfig and OrderServiceImpl.getMyOrder).
    @GetMapping("/{orderId}")
    public OrderResponse getMyOrder(@PathVariable String orderId) {
        return orderService.getMyOrder(orderId);
    }

    // Cancel a PENDING_PAYMENT order (ONLINE: its customer; POS: its creator - enforced in service layer)
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancelOrder(@PathVariable String orderId) {
        return orderService.cancelOrder(orderId);
    }

    // Mark a PENDING_PAYMENT order as PAYMENT_FAILED (ONLINE: its customer; POS: its creator - enforced in service layer)
    @PostMapping("/{orderId}/fail-payment")
    public OrderResponse failPayment(@PathVariable String orderId) {
        return orderService.failPayment(orderId);
    }
}
