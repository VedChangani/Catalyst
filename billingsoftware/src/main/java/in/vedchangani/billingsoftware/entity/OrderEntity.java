package in.vedchangani.billingsoftware.entity;

import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.io.SalesChannel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "tbl_orders", indexes = {
        // Unique (multiple NULLs allowed): one client checkout attempt = at most one order.
        // Declared as an index so Hibernate's ddl-auto=update also creates it on an existing table.
        @Index(name = "uk_tbl_orders_idempotency_key", columnList = "idempotency_key", unique = true),
        // Admin analytics: status-filtered, created_at-ranged aggregates. Non-unique, so it is safe
        // on existing data; ddl-auto=update adds it to the existing table when the name is missing.
        @Index(name = "idx_tbl_orders_status_created", columnList = "order_status, created_at")
})
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // Public order identifier used by every order/payment endpoint; must never repeat.
    @Column(unique = true, nullable = false)
    private String orderId;
    // Client-generated key of the checkout attempt that created this order (Idempotency-Key
    // header). NULL for orders created without one. Never returned to clients.
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    // SHA-256 (hex) of the normalised logical request that created the order; used only to detect
    // the same key being reused for a materially different request. Never returned to clients.
    @Column(name = "idempotency_fingerprint", length = 64)
    private String idempotencyFingerprint;

    private String customerName;
    private String phoneNumber;
    // Money: DECIMAL(19,4) so the historical DOUBLE values (whose unrounded 1% tax can carry up to
    // 4 decimals) convert without rounding; new orders are always whole paise (see util/Money).
    @Column(precision = 19, scale = 4)
    private BigDecimal subtotal;
    @Column(precision = 19, scale = 4)
    private BigDecimal tax;
    @Column(precision = 19, scale = 4)
    private BigDecimal grandTotal;
    private LocalDateTime createdAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItemEntity> items = new ArrayList<>();

    // Owning user for this order. Nullable to safely accommodate legacy orders that
    // were created before ownership tracking existed (user_id may be NULL for those rows).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    private UserEntity user;

    // Staff member who entered a POS sale: the cashier (POS creation is CASHIER-only; some older POS
    // orders were entered by an admin before that restriction). NULL for ONLINE orders and legacy
    // orders. This is creator identity only - it never grants or defines customer ownership.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = true)
    private UserEntity createdBy;

    // ONLINE or POS, set by the server. NULL on legacy orders created before channels existed;
    // their channel is unknown and is deliberately not guessed.
    @Enumerated(EnumType.STRING)
    private SalesChannel salesChannel;

    @Embedded
    private PaymentDetails paymentDetails;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

    // True only while this order holds an uncommitted stock reservation (a UPI order awaiting
    // payment). NULL on legacy orders created before inventory tracking: those never reserved
    // anything, so no reservation may ever be released on their behalf.
    private Boolean inventoryReserved;

    // Who may drive this order's payment lifecycle (Razorpay order, verify, cancel, fail):
    //  - POS order: only the staff member who entered it (createdBy). The associated customer
    //    (user) can see the purchase but does not operate the store's transaction.
    //  - ONLINE (and legacy, channel unknown) order: only the customer account (user);
    //    createdBy never grants access.
    public boolean canBeManagedBy(UserEntity actor) {
        if (actor == null || actor.getId() == null) {
            return false;
        }
        UserEntity manager = salesChannel == SalesChannel.POS ? createdBy : user;
        return manager != null && actor.getId().equals(manager.getId());
    }

    // "ORD<epoch-millis>-<8 hex>", e.g. ORD1758213456789-3F9A1C2B. The millisecond part alone
    // collided for orders created in the same millisecond; the random suffix makes that
    // practically impossible and the unique constraint on order_id guarantees it.
    static String generateOrderId() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "ORD" + System.currentTimeMillis() + "-" + suffix;
    }

    @PrePersist
    protected void onCreate() {
        this.orderId = generateOrderId();
        this.createdAt = LocalDateTime.now();
    }

}
