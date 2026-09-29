package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

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
    private String customerUserId;
    private String createdByUserId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer page;
    private Integer size;
    private String sort;
}
