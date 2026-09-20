package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Request body for PATCH /account/me. The target account is always the authenticated caller, so
// there is no user id. There is deliberately no role/enabled/authorities/permissions field: any
// such property in the JSON is ignored on deserialization.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AccountUpdateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 254, message = "Email must be at most 254 characters")
    private String email;

    // Format is checked (and normalized) by the service. May be blank only for an account that
    // has no mobile number yet (accounts created before mobile existed).
    private String mobile;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
