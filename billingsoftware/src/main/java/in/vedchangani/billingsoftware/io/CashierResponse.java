package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

// One row of the admin Manage Cashiers list. No password/hash or other security data.
// Sales metrics cover POS orders the cashier entered (createdBy), never ONLINE orders:
//   ordersProcessed = every such POS order, any status
//   posRevenue      = sum of grandTotal of those orders that are PAID
//   lastPosSaleAt   = createdAt of the newest one (null if none)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CashierResponse {
    private String userId;
    private String name;
    private String email;
    private String mobile;
    private boolean enabled;
    private Timestamp createdAt;
    private long ordersProcessed;
    private BigDecimal posRevenue;
    private LocalDateTime lastPosSaleAt;
}
