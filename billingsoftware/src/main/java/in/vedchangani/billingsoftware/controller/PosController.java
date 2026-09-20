package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.CustomerSummaryResponse;
import in.vedchangani.billingsoftware.io.OrderCreationResult;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PosOrderRequest;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// CASHIER only: every /pos/** route is gated in SecurityConfig (and POS creation is re-checked in the service).
@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
public class PosController {

    private final OrderService orderService;
    private final UserService userService;

    // Optional Idempotency-Key header, same semantics as POST /orders (201 created, 200 replay).
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createPosOrder(
            @Valid @RequestBody PosOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        OrderCreationResult result = orderService.createPosOrder(request, idempotencyKey);
        return ResponseEntity.status(result.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.getOrder());
    }

    // "My Sales": the calling cashier's own POS orders.
    @GetMapping("/sales")
    public List<OrderResponse> getMySales() {
        return orderService.getMySales();
    }

    @GetMapping("/customers")
    public List<CustomerSummaryResponse> searchCustomers(@RequestParam("search") String search) {
        return userService.searchCustomers(search);
    }
}
