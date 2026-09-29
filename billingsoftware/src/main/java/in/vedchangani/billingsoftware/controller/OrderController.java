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

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        OrderCreationResult result = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.status(result.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.getOrder());
    }

    @GetMapping("/latest")
    public List<OrderResponse> getLatestOrders() {
        return orderService.getLatestOrders();
    }

    @GetMapping("/my-orders")
    public List<OrderResponse> getMyOrders() {
        return orderService.getMyOrders();
    }

    @GetMapping("/{orderId}")
    public OrderResponse getMyOrder(@PathVariable String orderId) {
        return orderService.getMyOrder(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancelOrder(@PathVariable String orderId) {
        return orderService.cancelOrder(orderId);
    }

    @PostMapping("/{orderId}/fail-payment")
    public OrderResponse failPayment(@PathVariable String orderId) {
        return orderService.failPayment(orderId);
    }
}
