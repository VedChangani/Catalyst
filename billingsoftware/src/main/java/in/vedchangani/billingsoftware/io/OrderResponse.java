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
    // Null for legacy orders whose channel was never recorded.
    private SalesChannel salesChannel;
    // Staff member who entered a POS sale. Only populated in staff-facing responses (POS receipt,
    // My Sales, cashier order detail); never
    // in a customer's own order history.
    private StaffSummary createdBy;
    // The registered customer linked to the order (`user`); null for walk-in sales. Staff-facing
    // responses only - a customer's own history never needs it.
    private CustomerSummaryResponse customer;

    // Public view of the persisted PaymentDetails: the cryptographic razorpaySignature is never
    // returned to any client.
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
        // Historical snapshot taken from OrderItemEntity at purchase time, never the current catalog.
        private BigDecimal price;
        private Integer quantity;
        // snapshot price x quantity; null only for a legacy line missing either value.
        private BigDecimal lineTotal;
    }
}
