package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

// The caller's own account, as shown on the Account page. Role and status are read-only
// information. No password/hash, token version, user id or other security data.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AccountResponse {
    private String name;
    private String email;
    private String mobile;
    private String role;
    private boolean enabled;
    private Timestamp createdAt;
}
