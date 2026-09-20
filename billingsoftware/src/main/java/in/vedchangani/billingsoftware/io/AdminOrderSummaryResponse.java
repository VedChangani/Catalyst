package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// One row of the ADMIN order list. Deliberately no item lines (keeps the page light and avoids a
// per-order collection load) and no payment identifiers or signature.
//   customer  = the registered customer account associated with the purchase (null for walk-in)
//   createdBy = the staff member who entered a POS sale (null for ONLINE and legacy orders)
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
