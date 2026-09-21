package in.vedchangani.billingsoftware.io;

import in.vedchangani.billingsoftware.io.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

// Request body for the public password reset. No user id, role or mobile field. The code and both
// passwords are excluded from toString so logging this object can never leak them.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PasswordResetRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 254, message = "Email must be at most 254 characters")
    private String email;

    @ToString.Exclude
    @NotBlank(message = "Verification code is required")
    @Pattern(regexp = "^\\d{6}$", message = "Verification code must be exactly 6 digits")
    private String otp;

    @ToString.Exclude
    @NotBlank(message = "New password is required")
    @StrongPassword
    private String newPassword;

    @ToString.Exclude
    @NotBlank(message = "Please confirm the new password")
    private String confirmNewPassword;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
