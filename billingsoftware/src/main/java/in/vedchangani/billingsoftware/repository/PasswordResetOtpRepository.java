package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.PasswordResetOtpEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtpEntity, Long> {

    // Row lock (SELECT ... FOR UPDATE): concurrent verifications for the same account queue up, so
    // the second one sees the first one's outcome.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PasswordResetOtpEntity o WHERE o.user.id = :userId")
    Optional<PasswordResetOtpEntity> findByUserIdForUpdate(@Param("userId") Long userId);

    // Read-only view (no lock), for callers that only inspect state.
    Optional<PasswordResetOtpEntity> findByUserId(Long userId);

    // Atomic consume: only the statement that flips consumed_at from NULL reports 1 row, so of any
    // number of concurrent uses exactly one wins, even without the row lock.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetOtpEntity o SET o.consumedAt = :now WHERE o.id = :id AND o.consumedAt IS NULL")
    int consume(@Param("id") Long id, @Param("now") LocalDateTime now);

    // Atomic increment in the database - concurrent wrong guesses cannot overwrite each other.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetOtpEntity o SET o.attempts = o.attempts + 1 WHERE o.id = :id")
    int incrementAttempts(@Param("id") Long id);
}
