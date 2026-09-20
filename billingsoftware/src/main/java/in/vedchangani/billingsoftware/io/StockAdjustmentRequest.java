package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// The dedicated stock-adjustment operation (e.g. receiving new stock, or correcting a count).
// delta may be positive or negative; a zero delta is rejected here since it's not a real
// adjustment. Actual inventory safety (e.g. not dropping stockQuantity below reservedQuantity) is
// enforced by the repository/service, not this DTO.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StockAdjustmentRequest {

    @NotNull(message = "delta is required")
    private Integer delta;

    @AssertTrue(message = "delta must not be zero")
    public boolean isDeltaNonZero() {
        return delta == null || delta != 0;
    }
}
