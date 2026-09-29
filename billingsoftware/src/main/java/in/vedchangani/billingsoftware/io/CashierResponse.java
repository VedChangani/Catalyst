package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

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
