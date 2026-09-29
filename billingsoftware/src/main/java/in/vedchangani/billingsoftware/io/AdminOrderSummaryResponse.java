package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AdminOrderSummaryResponse {
    private String orderId;
    private LocalDateTime createdAt;
    private SalesChannel salesChannel;
    private String customerName;
    private String phoneNumber;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal grandTotal;
    private PaymentMethod paymentMethod;
    private PaymentDetails.PaymentStatus paymentStatus;
    private OrderStatus orderStatus;
    private CustomerSummaryResponse customer;
    private OrderResponse.StaffSummary createdBy;
}
