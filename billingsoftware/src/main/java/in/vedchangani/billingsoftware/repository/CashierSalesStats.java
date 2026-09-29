package in.vedchangani.billingsoftware.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface CashierSalesStats {
    Long getCashierId();

    Long getOrdersProcessed();

    BigDecimal getPosRevenue();

    LocalDateTime getLastSaleAt();
}
