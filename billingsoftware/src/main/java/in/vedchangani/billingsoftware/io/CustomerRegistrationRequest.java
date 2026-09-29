package in.vedchangani.billingsoftware.io;

import in.vedchangani.billingsoftware.io.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @NotBlank(message = "Mobile is required")
    private String mobile;

    @NotBlank(message = "Password is required")
    @StrongPassword
    private String password;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
