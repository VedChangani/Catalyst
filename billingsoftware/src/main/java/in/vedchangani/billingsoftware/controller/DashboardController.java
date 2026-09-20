package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.DashboardResponse;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.util.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final OrderService orderService;

    @GetMapping
    public DashboardResponse getDashboardData() {
        LocalDate today = LocalDate.now();
        // PAID revenue and PAID order count for today by effective paid time (same rule as Analytics)
        BigDecimal todaySale = orderService.sumSalesByDate(today);
        Long todayOrderCount = orderService.countByOrderDate(today);
        List<OrderResponse> recentOrders = orderService.findRecentOrders();
        return new DashboardResponse(
                Money.forResponse(Money.zeroIfNull(todaySale)),
                todayOrderCount != null ? todayOrderCount : 0,
                recentOrders
        );
    }
}
