package in.vedchangani.billingsoftware.repository;

/**
 * The ONE definition of "revenue" used by every report (Dashboard, Analytics, cashier metrics),
 * as JPQL fragments over an OrderEntity aliased {@code o}, so the reports cannot drift apart:
 *
 *  - only orderStatus = PAID counts (PENDING_PAYMENT, PAYMENT_FAILED, CANCELLED never do; a NULL
 *    legacy status matches nothing);
 *  - the revenue timestamp is the EFFECTIVE PAID TIME: paymentDetails.paidAt when present (UPI,
 *    set on verified payment), otherwise createdAt (CASH is PAID at creation and has no paidAt;
 *    legacy rows may lack it).
 *
 * Example: a UPI order created Sep 19 23:59 and verified Sep 20 00:01 is Sep 20 revenue; a CASH
 * order created Sep 19 is Sep 19 revenue.
 *
 * Date ranges are half-open [:start, :end), written as an OR of two plain range comparisons.
 */
public final class RevenueQueries {

    private RevenueQueries() {
    }

    public static final String EFFECTIVE_PAID_AT = "COALESCE(o.paymentDetails.paidAt, o.createdAt)";

    public static final String PAID = "o.orderStatus = 'PAID'";

    public static final String PAID_IN_RANGE = PAID + " AND ("
            + "(o.paymentDetails.paidAt >= :start AND o.paymentDetails.paidAt < :end) OR "
            + "(o.paymentDetails.paidAt IS NULL AND o.createdAt >= :start AND o.createdAt < :end))";

    // Sum of grand totals as BigDecimal (0bd = BigDecimal zero literal; no floating point anywhere).
    public static final String REVENUE = "COALESCE(SUM(o.grandTotal), 0bd)";
}
