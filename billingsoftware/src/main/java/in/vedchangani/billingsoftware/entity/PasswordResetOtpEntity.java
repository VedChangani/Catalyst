package in.vedchangani.billingsoftware.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * The one active password-reset code of a customer account (one row per user - a new code
 * overwrites the previous one, which invalidates it). Only a keyed HMAC of the code is stored,
 * never the code itself. Kept out of UserEntity on purpose: that entity is loaded on every request.
 */
@Entity
@Table(name = "tbl_password_reset_otp")
@Getter
@Setter
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class PasswordResetOtpEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    // Unique: at most one active code per account.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    // Hex HMAC-SHA256(secret, userId + ":" + code), 64 characters.
    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // Wrong guesses against the current code; the code is dead at the configured maximum.
    @Column(nullable = false)
    private int attempts;

    // Set atomically when the code is used; a consumed code can never be used again.
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    // Resend cooldown reference.
    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    // Hourly per-account send cap: codes sent since the window started.
    @Column(name = "send_window_start")
    private LocalDateTime sendWindowStart;

    @Column(name = "send_count", nullable = false)
    private int sendCount;
}
