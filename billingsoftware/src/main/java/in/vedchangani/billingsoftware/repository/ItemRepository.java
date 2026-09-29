package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.ItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ItemRepository extends JpaRepository<ItemEntity, Long> {

    Optional<ItemEntity> findByItemId(String id);

    Optional<ItemEntity> findByItemIdAndActiveTrue(String itemId);

    Integer countByCategoryId(Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ItemEntity i WHERE i.id = :id AND (i.reservedQuantity IS NULL OR i.reservedQuantity = 0)")
    int deleteUnreservedById(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.reservedQuantity = i.reservedQuantity + :qty, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND i.active = true " +
            "AND (i.stockQuantity - i.reservedQuantity) >= :qty")
    int reserveStock(@Param("itemId") String itemId, @Param("qty") int qty);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.stockQuantity = i.stockQuantity - :qty, " +
            "i.reservedQuantity = i.reservedQuantity - :qty, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND i.reservedQuantity >= :qty")
    int commitReservedStock(@Param("itemId") String itemId, @Param("qty") int qty);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.reservedQuantity = i.reservedQuantity - :qty, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND i.reservedQuantity >= :qty")
    int releaseReservedStock(@Param("itemId") String itemId, @Param("qty") int qty);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.stockQuantity = i.stockQuantity + :delta, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND (i.stockQuantity + :delta) >= i.reservedQuantity")
    int adjustStockQuantity(@Param("itemId") String itemId, @Param("delta") int delta);
}
