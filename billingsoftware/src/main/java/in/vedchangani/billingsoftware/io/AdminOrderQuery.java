package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

// Every field is an optional filter (AND semantics) except the paging/sorting trio, which
// default in the service. Validation of ranges/paging lives in OrderServiceImpl.getAdminOrders.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AdminOrderQuery {
    private String search;
    private OrderStatus orderStatus;
    private PaymentMethod paymentMethod;
    private PaymentDetails.PaymentStatus paymentStatus;
    private SalesChannel salesChannel;
    // userId of the registered customer associated with the purchase
    private String customerUserId;
    // userId of the staff member who created the POS sale
    private String createdByUserId;
    // inclusive; the whole of dateTo's day is included
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer page;
    private Integer size;
    // "field" or "field,asc|desc"; field is one of createdAt | grandTotal | orderId
    private String sort;
}
