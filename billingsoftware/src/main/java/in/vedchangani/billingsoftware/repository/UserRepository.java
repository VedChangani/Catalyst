package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.UserEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByUserId(String userId);

    // Customer lookup for the POS: registered customer accounts (ROLE_USER) only, matched on
    // name or email. `search` must already be LIKE-escaped with '!' as the escape character
    // ('!' rather than a backslash, which MySQL treats specially inside string literals).
    @Query("SELECT u FROM UserEntity u WHERE u.role = 'ROLE_USER' AND " +
            "(LOWER(u.name) LIKE LOWER(CONCAT('%', :search, '%')) ESCAPE '!' " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')) ESCAPE '!') ORDER BY u.name ASC")
    List<UserEntity> searchCustomers(@Param("search") String search, Pageable pageable);
}
