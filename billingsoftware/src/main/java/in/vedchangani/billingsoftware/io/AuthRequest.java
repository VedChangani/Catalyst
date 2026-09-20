package in.vedchangani.billingsoftware.io;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthRequest {

    // Email address or mobile number. "email" is still accepted so existing clients keep working.
    @JsonAlias("email")
    @NotBlank(message = "Email or mobile is required")
    private String identifier;

    @NotBlank(message = "Password is required")
    private String password;
}
