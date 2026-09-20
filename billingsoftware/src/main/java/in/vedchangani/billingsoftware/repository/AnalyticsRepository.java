package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.io.SalesChannel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// Read-only aggregate queries for the admin analytics dashboard. Every figure is computed by the
// database (SUM/COUNT/GROUP BY); nothing loads orders into Java.
//
// Revenue population and timestamp come from RevenueQueries - shared with the Dashboard so the two
// can never classify a paid order differently: orderStatus = PAID, bucketed by effective paid time
// (paidAt when present, otherwise createdAt), range half-open [:start, :end).
// Money is BigDecimal end to end.
public interface AnalyticsRepository extends Repository<OrderEntity, Long> {

    String PAID_IN_RANGE = RevenueQueries.PAID_IN_RANGE;

    String REVENUE = RevenueQueries.REVENUE;

    String EFFECTIVE_PAID_DAY = "CAST(" + RevenueQueries.EFFECTIVE_PAID_AT + " AS LocalDate)";

    interface Totals {
        BigDecimal getRevenue();

        Long getOrderCount();
    }

    interface DailyRow {
        LocalDate getDay();

        BigDecimal getRevenue();

        Long getOrderCount();
    }

    interface ChannelRow {
        SalesChannel getChannel();

        BigDecimal getRevenue();

        Long getOrderCount();
    }

    interface MethodRow {
        PaymentMethod getMethod();

        BigDecimal getRevenue();

        Long getOrderCount();
    }

    interface StatusRow {
        OrderStatus getStatus();

        Long getOrderCount();
    }

    interface TopProductRow {
        String getItemId();

        String getName();

        Long getQuantity();

        BigDecimal getRevenue();
    }

    interface InventoryRow {
        Long getLowStock();

        Long getOutOfStock();

        Long getUntracked();
    }

    // Top products come from the historical order-line snapshot (tbl_order_items name/price/
    // quantity), never from the current catalog. Lines with a NULL quantity (and NULL itemId) are
    // ignored. Name is MAX(name) - deterministic, NULLs ignored. Ties break on itemId ascending.
    // Callers pass PageRequest.of(0, 5) so the database returns only the top rows.
    //
    // By quantity: quantity = SUM(quantity) over lines with a quantity; revenue = SUM(price x
    // quantity) over the lines that also have a price (NULL-price lines add quantity but no revenue).
    @Query("SELECT i.itemId AS itemId, MAX(i.name) AS name, SUM(i.quantity) AS quantity, "
            + "COALESCE(SUM(i.price * i.quantity), 0bd) AS revenue "
            + "FROM OrderEntity o JOIN o.items i WHERE " + PAID_IN_RANGE
            + " AND i.itemId IS NOT NULL AND i.quantity IS NOT NULL "
            + "GROUP BY i.itemId ORDER BY SUM(i.quantity) DESC, i.itemId ASC")
    List<TopProductRow> topProductsByQuantity(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                              Pageable pageable);

    // By revenue: only lines with both a price and a quantity take part (a NULL price is excluded,
    // not treated as 0), so quantity here is the quantity of the priced lines. Pre-tax.
    @Query("SELECT i.itemId AS itemId, MAX(i.name) AS name, SUM(i.quantity) AS quantity, "
            + "SUM(i.price * i.quantity) AS revenue "
            + "FROM OrderEntity o JOIN o.items i WHERE " + PAID_IN_RANGE
            + " AND i.itemId IS NOT NULL AND i.quantity IS NOT NULL AND i.price IS NOT NULL "
            + "GROUP BY i.itemId ORDER BY SUM(i.price * i.quantity) DESC, i.itemId ASC")
    List<TopProductRow> topProductsByRevenue(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                             Pageable pageable);

    // Point-in-time counts over the current catalog. The three buckets are disjoint: untracked
    // needs a NULL, low/out need all of active/stock/reserved non-null. Inactive items are only
    // ever counted as untracked (when their fields are NULL), never as low/out of stock.
    // A NULL lowStockThreshold makes an item not low-stock (the comparison is unknown) but it can
    // still be out of stock.
    @Query("SELECT "
            + "COALESCE(SUM(CASE WHEN i.active = true AND i.stockQuantity IS NOT NULL AND i.reservedQuantity IS NOT NULL "
            + "AND (i.stockQuantity - i.reservedQuantity) > 0 AND i.lowStockThreshold IS NOT NULL "
            + "AND (i.stockQuantity - i.reservedQuantity) <= i.lowStockThreshold THEN 1 ELSE 0 END), 0) AS lowStock, "
            + "COALESCE(SUM(CASE WHEN i.active = true AND i.stockQuantity IS NOT NULL AND i.reservedQuantity IS NOT NULL "
            + "AND (i.stockQuantity - i.reservedQuantity) <= 0 THEN 1 ELSE 0 END), 0) AS outOfStock, "
            + "COALESCE(SUM(CASE WHEN i.active IS NULL OR i.stockQuantity IS NULL OR i.reservedQuantity IS NULL "
            + "THEN 1 ELSE 0 END), 0) AS untracked "
            + "FROM ItemEntity i")
    InventoryRow inventorySummary();

    @Query("SELECT " + REVENUE + " AS revenue, COUNT(o) AS orderCount FROM OrderEntity o WHERE " + PAID_IN_RANGE)
    Totals paidTotals(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT " + EFFECTIVE_PAID_DAY + " AS day, "
            + REVENUE + " AS revenue, COUNT(o) AS orderCount FROM OrderEntity o WHERE " + PAID_IN_RANGE
            + " GROUP BY " + EFFECTIVE_PAID_DAY
            + " ORDER BY " + EFFECTIVE_PAID_DAY)
    List<DailyRow> paidByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // channel is NULL for legacy orders; the service reports that bucket as UNKNOWN.
    @Query("SELECT o.salesChannel AS channel, " + REVENUE + " AS revenue, COUNT(o) AS orderCount "
            + "FROM OrderEntity o WHERE " + PAID_IN_RANGE + " GROUP BY o.salesChannel")
    List<ChannelRow> paidByChannel(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT o.paymentMethod AS method, " + REVENUE + " AS revenue, COUNT(o) AS orderCount "
            + "FROM OrderEntity o WHERE " + PAID_IN_RANGE + " GROUP BY o.paymentMethod")
    List<MethodRow> paidByPaymentMethod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Orders created in the range, by their CURRENT status (there is no status history).
    @Query("SELECT o.orderStatus AS status, COUNT(o) AS orderCount FROM OrderEntity o "
            + "WHERE o.orderStatus IS NOT NULL AND o.createdAt >= :start AND o.createdAt < :end "
            + "GROUP BY o.orderStatus")
    List<StatusRow> countByStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT o.orderStatus AS status, COUNT(o) AS orderCount FROM OrderEntity o "
            + "WHERE o.orderStatus IS NOT NULL AND o.paymentMethod = 'UPI' "
            + "AND o.createdAt >= :start AND o.createdAt < :end GROUP BY o.orderStatus")
    List<StatusRow> countUpiByStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}