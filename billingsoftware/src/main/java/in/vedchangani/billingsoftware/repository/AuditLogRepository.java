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

/**
 * Deliberately extends the bare Repository marker (not JpaRepository/CrudRepository): the only
 * operations that exist are insert and paged reads. There is no update or delete method, so no
 * application code path can modify or remove an audit record.
 */
public interface AuditLogRepository extends Repository<AuditLogEntity, Long> {

    AuditLogEntity save(AuditLogEntity entry);

    // "My activity": filtered by the actor's database id in the query itself.
    Page<AuditLogEntity> findByActorUserId(Long actorUserId, Pageable pageable);

    // System activity (ADMIN). Every filter is optional; a null parameter means "no constraint".
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
