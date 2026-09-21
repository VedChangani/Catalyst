package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request body for the public forgot-password request. Only the email: no user id, role or mobile
// is accepted (unknown JSON properties are ignored). Ownership is proven later by the emailed code.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ForgotPasswordRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 254, message = "Email must be at most 254 characters")
    private String email;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
