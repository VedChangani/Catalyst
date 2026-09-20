package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.io.AdminOrderQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// Builds the ADMIN order-list query as a JPA Criteria specification: every filter is a bound
// parameter (no SQL string building) and is evaluated by the database, together with paging.
public final class OrderSpecifications {

    private static final char LIKE_ESCAPE = '!';

    private OrderSpecifications() {
    }

    public static Specification<OrderEntity> matching(AdminOrderQuery q) {
        return (root, query, cb) -> {
            // The list response shows the customer and the creating staff member, so load both
            // in the same query (many-to-one fetches: rows are not multiplied and paging stays in
            // the database). Skipped for the count query, which selects a Long.
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("user", JoinType.LEFT);
                root.fetch("createdBy", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();

            if (q.getSearch() != null && !q.getSearch().isBlank()) {
                String pattern = "%" + escapeLike(q.getSearch().trim().toLowerCase()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("orderId")), pattern, LIKE_ESCAPE),
                        cb.like(cb.lower(root.<String>get("customerName")), pattern, LIKE_ESCAPE),
                        cb.like(cb.lower(root.<String>get("phoneNumber")), pattern, LIKE_ESCAPE)));
            }
            if (q.getOrderStatus() != null) {
                predicates.add(cb.equal(root.get("orderStatus"), q.getOrderStatus()));
            }
            if (q.getPaymentMethod() != null) {
                predicates.add(cb.equal(root.get("paymentMethod"), q.getPaymentMethod()));
            }
            if (q.getPaymentStatus() != null) {
                predicates.add(cb.equal(root.get("paymentDetails").get("status"), q.getPaymentStatus()));
            }
            if (q.getSalesChannel() != null) {
                predicates.add(cb.equal(root.get("salesChannel"), q.getSalesChannel()));
            }
            if (q.getCustomerUserId() != null && !q.getCustomerUserId().isBlank()) {
                predicates.add(cb.equal(root.get("user").get("userId"), q.getCustomerUserId()));
            }
            if (q.getCreatedByUserId() != null && !q.getCreatedByUserId().isBlank()) {
                predicates.add(cb.equal(root.get("createdBy").get("userId"), q.getCreatedByUserId()));
            }
            if (q.getDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), q.getDateFrom().atStartOfDay()));
            }
            if (q.getDateTo() != null) {
                // exclusive start of the next day = inclusive of all of dateTo
                predicates.add(cb.lessThan(root.get("createdAt"), q.getDateTo().plusDays(1).atStartOfDay()));
            }
            if (q.getMinAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("grandTotal"), q.getMinAmount()));
            }
            if (q.getMaxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("grandTotal"), q.getMaxAmount()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
