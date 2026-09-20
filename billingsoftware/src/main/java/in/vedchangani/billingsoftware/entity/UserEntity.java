package in.vedchangani.billingsoftware.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;

@Entity
@Table(name = "tbl_users", indexes = {
        // Email and mobile are the login identifiers, so each must resolve to exactly one account.
        // mobile is nullable (accounts created before it existed have none); a unique index still
        // allows any number of NULLs in MySQL.
        @Index(name = "uk_tbl_users_email", columnList = "email", unique = true),
        @Index(name = "uk_tbl_users_mobile", columnList = "mobile", unique = true)
})
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true)
    private String userId;
    // Stored normalized (trimmed, lower-case) - see ContactNormalizer.normalizeEmail.
    private String email;
    // Canonical 10-digit mobile number - see ContactNormalizer.normalizeMobile.
    @Column(length = 10)
    private String mobile;
    private String password;
    private String role;
    private String name;
    // Account status. false = deactivated: cannot log in, and any JWT it already holds stops
    // working (JwtRequestFilter reloads the account on every request). Accounts are deactivated,
    // never deleted, so historical orders keep their createdBy/user relationships.
    // The column DEFAULT 1 makes ddl-auto=update fill existing rows as enabled; a NULL (should one
    // ever exist) is also treated as enabled - see isAccountEnabled().
    @Builder.Default
    @ColumnDefault("1")
    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;
    // Session generation. Every JWT carries the version current when it was issued, and a token
    // is only accepted while it still matches (see JwtRequestFilter). Incrementing it therefore
    // revokes every token the account holds - done on password reset and on deactivation.
    // Server-side only: never accepted from or returned to clients. DEFAULT 0 fills existing rows.
    @Builder.Default
    @ColumnDefault("0")
    @Column(name = "token_version")
    private Integer tokenVersion = 0;
    @CreationTimestamp
    @Column(updatable = false)
    private Timestamp createdAt;
    @UpdateTimestamp
    private Timestamp updatedAt;

    public boolean isAccountEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    public int currentTokenVersion() {
        return tokenVersion == null ? 0 : tokenVersion;
    }
}
