package in.vedchangani.billingsoftware.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Projection of one row of OrderEntityRepository.cashierSalesStats (grouped by cashier).
public interface CashierSalesStats {
    Long getCashierId();

    Long getOrdersProcessed();

    BigDecimal getPosRevenue();

    LocalDateTime getLastSaleAt();
}
