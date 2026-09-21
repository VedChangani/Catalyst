package in.vedchangani.billingsoftware.entity;

import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * One persisted activity/audit event. Write-once: @Immutable, no setters, every column
 * updatable=false, and AuditLogRepository exposes no update or delete operation.
 *
 * The actor is stored as a snapshot (database id, public id, name and role AT THE TIME of the
 * event) rather than as a foreign-key relationship, so the audit trail never depends on - and
 * can never block or cascade with - later changes to the user table. actorUserId is null only for
 * SYSTEM events.
 *
 * `details` holds a small JSON object built by server code only (see AuditServiceImpl); it never
 * contains passwords, hashes, tokens, token versions, signatures, secrets or raw request bodies.
 */
@Entity
@Immutable
@Table(name = "tbl_audit_log", indexes = {
        // "my activity": one actor's events, newest first
        @Index(name = "idx_tbl_audit_log_actor_created", columnList = "actor_user_id, created_at"),
        // system activity: everything newest first, optionally narrowed by action
        @Index(name = "idx_tbl_audit_log_created", columnList = "created_at"),
        @Index(name = "idx_tbl_audit_log_action_created", columnList = "action, created_at")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuditLogEntity {

    public static final String SYSTEM_ROLE = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(name = "actor_public_id", length = 64, updatable = false)
    private String actorPublicId;

    @Column(name = "actor_name", length = 100, updatable = false)
    private String actorName;

    @Column(name = "actor_role", length = 20, nullable = false, updatable = false)
    private String actorRole;

    // VARCHAR, not a native MySQL ENUM: an ENUM column is fixed at creation and ddl-auto=update never
    // extends it, so every new AuditAction value would fail to insert on an existing database.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 40, nullable = false, updatable = false)
    private AuditAction action;

    // VARCHAR, not a native MySQL ENUM: an ENUM column is fixed at creation and ddl-auto=update never
    // extends it, so every new AuditAction value would fail to insert on an existing database.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20, updatable = false)
    private AuditTargetType targetType;

    @Column(name = "target_id", length = 64, updatable = false)
    private String targetId;

    @Column(name = "details", length = 1000, updatable = false)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        // microsecond precision = what the column stores, so ordering in memory and in SQL agree
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
