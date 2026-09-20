package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Admin analytics snapshot. Only aggregates - no customer, order-id or payment identifiers.
// Revenue figures cover PAID orders only, bucketed by their effective paid time
// (paidAt, falling back to createdAt for CASH/legacy orders). Order-status counts are bucketed by
// createdAt. Money values are rounded to 2 decimal places, shares to 4.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsResponse {

    private Range range;
    private Kpis kpis;
    private List<DailyPoint> daily;
    private List<ChannelBreakdown> channels;
    private List<PaymentMethodBreakdown> paymentMethods;
    // Always contains all four statuses (zero-filled).
    private Map<OrderStatus, Long> orderStatusCounts;
    // PAID / (PAID + PAYMENT_FAILED + CANCELLED) over UPI orders created in the range; null when
    // there is no settled UPI order (CASH is always PAID, so it says nothing about payment success).
    private Double upiSuccessRate;
    // At most 5 each, from the order-line snapshots of PAID orders paid in the range. Never null.
    private List<TopProduct> topByQuantity;
    private List<TopProduct> topByRevenue;
    // Current point-in-time stock state; ignores the selected date range.
    private InventorySummary inventory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopProduct {
        private String itemId;
        // Historical order-line name (MAX over the matching lines); null only if every line lacks one.
        private String name;
        private Long quantity;
        // Product-line revenue = SUM(snapshot price x quantity): pre-tax, so it does not add up to
        // the tax-inclusive order revenue in kpis.
        private BigDecimal revenue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventorySummary {
        // active, available = stock - reserved, 0 < available <= lowStockThreshold
        private Long lowStock;
        // active, available <= 0
        private Long outOfStock;
        // active, stockQuantity or reservedQuantity is NULL (legacy items never backfilled), so
        // availability/sellability is unknown. Counted regardless of the other fields; never also low/out.
        private Long untracked;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Range {
        // today | 7d | 30d | custom
        private String preset;
        // both inclusive
        private LocalDate from;
        private LocalDate to;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Kpis {
        private BigDecimal revenue;
        private Long paidOrders;
        // 0.0 when there are no paid orders
        private BigDecimal averageOrderValue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyPoint {
        private LocalDate date;
        private BigDecimal revenue;
        private Long orders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelBreakdown {
        // ONLINE | POS | UNKNOWN (legacy orders whose channel was never recorded)
        private String channel;
        private BigDecimal revenue;
        private Long orders;
        private Double revenueShare;
        private Double orderShare;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentMethodBreakdown {
        // CASH | UPI | UNKNOWN
        private String method;
        private BigDecimal revenue;
        private Long orders;
        private Double revenueShare;
        private Double orderShare;
    }
}