package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request body for PATCH /admin/cashiers/{cashierId}/status. Only the status can be changed here -
// never the role.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CashierStatusRequest {

    @NotNull(message = "enabled is required")
    private Boolean enabled;
}
