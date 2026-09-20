package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request body for the public POST /register (customer self-registration). There is deliberately
// no role/authority/enabled field: every account created through this endpoint is ROLE_USER, and
// any extra JSON property (e.g. "role":"ROLE_ADMIN") is ignored on deserialization.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CustomerRegistrationRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 254, message = "Email must be at most 254 characters")
    private String email;

    // Format is checked (and normalized) by the service - see ContactNormalizer.normalizeMobile.
    @NotBlank(message = "Mobile is required")
    private String mobile;

    // 72 is BCrypt's input limit; anything longer would be silently truncated.
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).*$", message = "Password must contain at least one letter and one number")
    private String password;

    // Trimmed before validation so stray whitespace (e.g. from autofill) does not fail @Email;
    // lower-casing happens in the service via ContactNormalizer.
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
