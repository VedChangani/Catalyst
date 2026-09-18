package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderEntityRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {

    Optional<OrderEntity> findByOrderId(String orderId);

    // Indexed lookup (unique index on idempotency_key) used to replay an idempotent create.
    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    // Locking read (SELECT ... FOR UPDATE) of one order row, for payment-lifecycle transitions.
    // A plain read would let two transitions on the same order (verify vs cancel, or a duplicate
    // verify) both see PENDING_PAYMENT and both mutate stock. With the row lock the second caller
    // waits for the first to commit, then reads the latest committed status, so the existing
    // status guards reject it. Must be called inside a transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.orderId = :orderId")
    Optional<OrderEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

    List<OrderEntity> findAllByOrderByCreatedAtDesc();

    // Ownership-filtered lookup for "my orders": pushed down to the database rather than
    // fetching every order and filtering in Java, so a user can never receive rows they
    // don't own even if application code has a bug elsewhere.
    List<OrderEntity> findByUser_IdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT SUM(o.grandTotal) FROM OrderEntity o WHERE DATE(o.createdAt) = :date AND o.orderStatus = 'PAID'")
    Double sumSalesByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(o) FROM OrderEntity o WHERE DATE(o.createdAt) = :date AND o.orderStatus = 'PAID'")
    Long countByOrderDate(@Param("date") LocalDate date);

    @Query("SELECT o FROM OrderEntity o ORDER BY o.createdAt DESC")
    List<OrderEntity> findRecentOrders(Pageable pageable);

    // Candidates for lazy reservation expiry: orders still awaiting payment, older than the
    // cutoff, that actually hold a reservation (legacy orders with a NULL flag never do), and that
    // touch at least one of the given items. A plain read - the claim below is what's atomic.
    @Query("SELECT DISTINCT o FROM OrderEntity o JOIN o.items i " +
            "WHERE o.orderStatus = :status AND o.inventoryReserved = true " +
            "AND o.createdAt < :cutoff AND i.itemId IN :itemIds")
    List<OrderEntity> findStaleReservedOrdersContainingItems(@Param("status") OrderStatus status,
                                                             @Param("cutoff") LocalDateTime cutoff,
                                                             @Param("itemIds") Collection<String> itemIds);

    // Atomically claims one stale order for expiry: moves it PENDING_PAYMENT -> PAYMENT_FAILED and
    // clears its reservation flag in a single guarded UPDATE. Returns 1 only for the one caller
    // that wins the claim; any concurrent or repeated attempt gets 0 and must not release the
    // order's stock again.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OrderEntity o SET o.orderStatus = :expiredStatus, " +
            "o.paymentDetails.status = :failedPaymentStatus, o.inventoryReserved = false " +
            "WHERE o.id = :id AND o.orderStatus = :pendingStatus AND o.inventoryReserved = true")
    int claimStaleReservationForExpiry(@Param("id") Long id,
                                       @Param("pendingStatus") OrderStatus pendingStatus,
                                       @Param("expiredStatus") OrderStatus expiredStatus,
                                       @Param("failedPaymentStatus") PaymentDetails.PaymentStatus failedPaymentStatus);

}
