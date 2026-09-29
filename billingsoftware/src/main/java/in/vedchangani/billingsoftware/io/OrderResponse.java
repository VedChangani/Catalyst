package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderResponse {
    private String orderId;
    private String customerName;
    private String phoneNumber;
    private List<OrderResponse.OrderItemResponse> items;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal grandTotal;
    private PaymentMethod paymentMethod;
    private LocalDateTime createdAt;
    private PaymentSummary paymentDetails;
    private OrderStatus orderStatus;
    private String paymentStatus;
    private SalesChannel salesChannel;
    private StaffSummary createdBy;
    private CustomerSummaryResponse customer;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class PaymentSummary {
        private String razorpayOrderId;
        private String razorpayPaymentId;
        private PaymentDetails.PaymentStatus status;
        private LocalDateTime paidAt;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class StaffSummary {
        private String userId;
        private String name;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class OrderItemResponse {
        private String itemId;
        private String name;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal lineTotal;
    }
}
