package in.vedchangani.billingsoftware.repository;

public final class RevenueQueries {

    private RevenueQueries() {
    }

    public static final String EFFECTIVE_PAID_AT = "COALESCE(o.paymentDetails.paidAt, o.createdAt)";

    public static final String PAID = "o.orderStatus = 'PAID'";

    public static final String PAID_IN_RANGE = PAID + " AND ("
            + "(o.paymentDetails.paidAt >= :start AND o.paymentDetails.paidAt < :end) OR "
            + "(o.paymentDetails.paidAt IS NULL AND o.createdAt >= :start AND o.createdAt < :end))";

    public static final String REVENUE = "COALESCE(SUM(o.grandTotal), 0bd)";
}
