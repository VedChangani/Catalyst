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
    private String email;
    @Column(length = 10)
    private String mobile;
    private String password;
    private String role;
    private String name;
    @Builder.Default
    @ColumnDefault("1")
    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;
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
