package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CashierStatusRequest {

    @NotNull(message = "enabled is required")
    private Boolean enabled;
}
