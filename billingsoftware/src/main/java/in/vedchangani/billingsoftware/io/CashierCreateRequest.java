package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request body for POST /admin/cashiers. Same fields and rules as customer registration. There is
// deliberately no role/enabled/authorities/userId field: the server always creates an enabled
// ROLE_CASHIER, and any such property in the JSON is ignored on deserialization.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CashierCreateRequest {

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

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).*$", message = "Password must contain at least one letter and one number")
    private String password;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
