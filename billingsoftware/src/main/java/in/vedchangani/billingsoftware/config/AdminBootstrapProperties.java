package in.vedchangani.billingsoftware.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    @ToString.Exclude
    private String password;
}
