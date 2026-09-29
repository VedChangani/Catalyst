package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.SalesChannel;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderEntityRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {

    Optional<OrderEntity> findByOrderId(String orderId);

    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.orderId = :orderId")
    Optional<OrderEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

    List<OrderEntity> findAllByOrderByCreatedAtDesc();

    List<OrderEntity> findByUser_IdOrderByCreatedAtDesc(Long userId);

    List<OrderEntity> findByCreatedBy_IdAndSalesChannelOrderByCreatedAtDesc(Long createdById, SalesChannel salesChannel);

    @Query("SELECT o.createdBy.id AS cashierId, COUNT(o) AS ordersProcessed, " +
            "COALESCE(SUM(CASE WHEN o.orderStatus = :paid THEN o.grandTotal ELSE 0bd END), 0bd) AS posRevenue, " +
            "MAX(o.createdAt) AS lastSaleAt " +
            "FROM OrderEntity o WHERE o.salesChannel = :pos AND o.createdBy.id IN :cashierIds " +
            "GROUP BY o.createdBy.id")
    List<CashierSalesStats> cashierSalesStats(@Param("cashierIds") Collection<Long> cashierIds,
                                              @Param("pos") SalesChannel pos,
                                              @Param("paid") OrderStatus paid);

    @Query("SELECT " + RevenueQueries.REVENUE + " FROM OrderEntity o WHERE " + RevenueQueries.PAID_IN_RANGE)
    BigDecimal sumPaidRevenue(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(o) FROM OrderEntity o WHERE " + RevenueQueries.PAID_IN_RANGE)
    Long countPaidOrders(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT o FROM OrderEntity o ORDER BY o.createdAt DESC")
    List<OrderEntity> findRecentOrders(Pageable pageable);

    @Query("SELECT DISTINCT o FROM OrderEntity o JOIN o.items i " +
            "WHERE o.orderStatus = :status AND o.inventoryReserved = true " +
            "AND o.createdAt < :cutoff AND i.itemId IN :itemIds")
    List<OrderEntity> findStaleReservedOrdersContainingItems(@Param("status") OrderStatus status,
                                                             @Param("cutoff") LocalDateTime cutoff,
                                                             @Param("itemIds") Collection<String> itemIds);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OrderEntity o SET o.orderStatus = :expiredStatus, " +
            "o.paymentDetails.status = :failedPaymentStatus, o.inventoryReserved = false " +
            "WHERE o.id = :id AND o.orderStatus = :pendingStatus AND o.inventoryReserved = true")
    int claimStaleReservationForExpiry(@Param("id") Long id,
                                       @Param("pendingStatus") OrderStatus pendingStatus,
                                       @Param("expiredStatus") OrderStatus expiredStatus,
                                       @Param("failedPaymentStatus") PaymentDetails.PaymentStatus failedPaymentStatus);

}
