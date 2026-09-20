package in.vedchangani.billingsoftware.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Developer-provisioned initial ADMIN account (see AdminBootstrap). Bound from app.admin.bootstrap.*,
 * which application.properties maps to the APP_ADMIN_* environment variables (e.g. in the root .env).
 * There are no default credentials: when the values are absent nothing is created.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Component
@ConfigurationProperties(prefix = "app.admin.bootstrap")
public class AdminBootstrapProperties {

    private boolean enabled;
    private String name;
    private String email;
    private String mobile;

    // never printed: excluded from toString so it cannot leak through logging of this object
    @ToString.Exclude
    private String password;
}
