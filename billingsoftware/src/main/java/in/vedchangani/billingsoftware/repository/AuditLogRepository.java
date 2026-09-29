package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.AuditLogEntity;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuditLogRepository extends Repository<AuditLogEntity, Long> {

    AuditLogEntity save(AuditLogEntity entry);

    Page<AuditLogEntity> findByActorUserId(Long actorUserId, Pageable pageable);

    @Query("SELECT a FROM AuditLogEntity a WHERE " +
            "(:action IS NULL OR a.action = :action) " +
            "AND (:actorUserId IS NULL OR a.actorUserId = :actorUserId) " +
            "AND (:actorRole IS NULL OR a.actorRole = :actorRole) " +
            "AND (:targetType IS NULL OR a.targetType = :targetType) " +
            "AND (:fromInclusive IS NULL OR a.createdAt >= :fromInclusive) " +
            "AND (:toExclusive IS NULL OR a.createdAt < :toExclusive)")
    Page<AuditLogEntity> search(@Param("action") AuditAction action,
                                @Param("actorUserId") Long actorUserId,
                                @Param("actorRole") String actorRole,
                                @Param("targetType") AuditTargetType targetType,
                                @Param("fromInclusive") LocalDateTime fromInclusive,
                                @Param("toExclusive") LocalDateTime toExclusive,
                                Pageable pageable);
}
