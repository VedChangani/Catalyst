package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request body for POST /admin/cashiers/{cashierId}/reset-password: the new password only, held
// to the same policy as account creation.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CashierPasswordResetRequest {

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).*$", message = "Password must contain at least one letter and one number")
    private String password;
}
