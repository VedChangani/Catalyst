package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.UserEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByMobile(String mobile);

    Optional<UserEntity> findByUserId(String userId);

    List<UserEntity> findByRoleOrderByNameAsc(String role);

    boolean existsByRole(String role);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.password = :passwordHash, " +
            "u.tokenVersion = COALESCE(u.tokenVersion, 0) + 1 WHERE u.id = :id")
    int updatePasswordAndRevokeTokens(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.enabled = false, " +
            "u.tokenVersion = COALESCE(u.tokenVersion, 0) + 1 WHERE u.id = :id")
    int disableAndRevokeTokens(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.enabled = true WHERE u.id = :id")
    int enable(@Param("id") Long id);

    @Query("SELECT u FROM UserEntity u WHERE u.role = 'ROLE_USER' AND " +
            "(LOWER(u.name) LIKE LOWER(CONCAT('%', :search, '%')) ESCAPE '!' " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')) ESCAPE '!') ORDER BY u.name ASC")
    List<UserEntity> searchCustomers(@Param("search") String search, Pageable pageable);
}
