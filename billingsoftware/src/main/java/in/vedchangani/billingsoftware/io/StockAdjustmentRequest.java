package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
