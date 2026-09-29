package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    private Map<OrderStatus, Long> orderStatusCounts;
    private Double upiSuccessRate;
    private List<TopProduct> topByQuantity;
    private List<TopProduct> topByRevenue;
    private InventorySummary inventory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopProduct {
        private String itemId;
        private String name;
        private Long quantity;
        private BigDecimal revenue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventorySummary {
        private Long lowStock;
        private Long outOfStock;
        private Long untracked;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Range {
        private String preset;
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
        private String method;
        private BigDecimal revenue;
        private Long orders;
        private Double revenueShare;
        private Double orderShare;
    }
}