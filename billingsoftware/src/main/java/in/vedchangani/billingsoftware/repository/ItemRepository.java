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

    // Deletes the item row by primary key, but only while nothing is reserved against it, in ONE
    // statement. Returns the number of rows deleted (0 = not there, or it holds a reservation).
    //
    // Why not itemRepository.delete(entity): Spring Data treats an entity whose @Version is null as
    // "new" and returns WITHOUT running any SQL. Rows created before inventory tracking have a NULL
    // version, so delete(entity) silently did nothing for them while the API still answered 204.
    // A JPQL DELETE never consults the version, and the reservation guard is checked atomically
    // (a NULL reservedQuantity on such legacy rows means nothing is reserved).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ItemEntity i WHERE i.id = :id AND (i.reservedQuantity IS NULL OR i.reservedQuantity = 0)")
    int deleteUnreservedById(@Param("id") Long id);

    // Every stock mutation below also bumps @Version. JPQL bulk UPDATEs don't do that on their
    // own, and ItemServiceImpl.update saves the whole row (stock columns included): without the
    // bump, an admin edit loaded before a reservation would pass its version check and silently
    // write the old reservedQuantity back. With it, that edit fails with a 409 instead.

    // Atomic conditional reserve: only succeeds (returns 1) when the item is active and its
    // available stock (stockQuantity - reservedQuantity) covers the requested quantity. Under
    // MySQL InnoDB this WHERE-guarded UPDATE is itself the compare-and-swap that prevents
    // overselling under concurrent requests - no separate SELECT-then-check is ever done.
    // A result of 0 means insufficient available stock, an inactive item, or a missing item, and
    // must be treated by the caller as a hard failure, never a no-op success.
    // Must run inside a caller-managed transaction (none is opened here) - service-layer wiring
    // and the @Transactional boundary are introduced in a later batch.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.reservedQuantity = i.reservedQuantity + :qty, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND i.active = true " +
            "AND (i.stockQuantity - i.reservedQuantity) >= :qty")
    int reserveStock(@Param("itemId") String itemId, @Param("qty") int qty);

    // Commits a previously-made reservation: moves :qty out of reservedQuantity and out of
    // stockQuantity together. Guarded so it can never drop reservedQuantity below zero or commit
    // more than was actually reserved. A result of 0 means the reservation wasn't there for at
    // least :qty and must be treated by the caller as a hard failure - the corresponding order
    // must not be marked PAID in that case.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.stockQuantity = i.stockQuantity - :qty, " +
            "i.reservedQuantity = i.reservedQuantity - :qty, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND i.reservedQuantity >= :qty")
    int commitReservedStock(@Param("itemId") String itemId, @Param("qty") int qty);

    // Releases a previously-made reservation without touching stockQuantity. Same
    // reservedQuantity >= :qty guard as commitReservedStock. A result of 0 must be treated by the
    // caller as a hard failure - the corresponding order must not be marked
    // CANCELLED/PAYMENT_FAILED in that case.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.reservedQuantity = i.reservedQuantity - :qty, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND i.reservedQuantity >= :qty")
    int releaseReservedStock(@Param("itemId") String itemId, @Param("qty") int qty);

    // Admin stock correction/restock. delta may be positive or negative, but the result must
    // never leave stockQuantity below reservedQuantity - reserved-but-uncommitted stock must
    // always remain physically covered. A result of 0 must be treated by the caller as a
    // conflict, not a silent no-op.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ItemEntity i SET i.stockQuantity = i.stockQuantity + :delta, i.version = i.version + 1 " +
            "WHERE i.itemId = :itemId AND (i.stockQuantity + :delta) >= i.reservedQuantity")
    int adjustStockQuantity(@Param("itemId") String itemId, @Param("delta") int delta);
}
