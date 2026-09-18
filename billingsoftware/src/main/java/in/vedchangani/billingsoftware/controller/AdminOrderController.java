package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// ADMIN-only: gated by the existing /admin/** rule in SecurityConfig. Only collects the query
// parameters; validation and the database query live in OrderService.
@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    @GetMapping
    public PagedResponse<AdminOrderSummaryResponse> getOrders(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) OrderStatus orderStatus,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) PaymentDetails.PaymentStatus paymentStatus,
            @RequestParam(required = false) SalesChannel salesChannel,
            @RequestParam(required = false) String customerUserId,
            @RequestParam(required = false) String createdByUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(required = false) Double maxAmount,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return orderService.getAdminOrders(AdminOrderQuery.builder()
                .search(search).orderStatus(orderStatus).paymentMethod(paymentMethod)
                .paymentStatus(paymentStatus).salesChannel(salesChannel)
                .customerUserId(customerUserId).createdByUserId(createdByUserId)
                .dateFrom(dateFrom).dateTo(dateTo).minAmount(minAmount).maxAmount(maxAmount)
                .page(page).size(size).sort(sort)
                .build());
    }
}
