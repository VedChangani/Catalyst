package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request body for PATCH /account/me/password. Same password policy as registration.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PasswordChangeRequest {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).*$", message = "Password must contain at least one letter and one number")
    private String newPassword;

    @NotBlank(message = "Please confirm the new password")
    private String confirmNewPassword;
}
