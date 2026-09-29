package in.vedchangani.billingsoftware.io;

import in.vedchangani.billingsoftware.io.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CashierPasswordResetRequest {

    @NotBlank(message = "Password is required")
    @StrongPassword
    private String password;
}
