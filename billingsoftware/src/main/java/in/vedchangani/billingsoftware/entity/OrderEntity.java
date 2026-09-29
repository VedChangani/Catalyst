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
        @Index(name = "uk_tbl_orders_idempotency_key", columnList = "idempotency_key", unique = true),
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
    @Column(unique = true, nullable = false)
    private String orderId;
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "idempotency_fingerprint", length = 64)
    private String idempotencyFingerprint;

    private String customerName;
    private String phoneNumber;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = true)
    private UserEntity createdBy;

    @Enumerated(EnumType.STRING)
    private SalesChannel salesChannel;

    @Embedded
    private PaymentDetails paymentDetails;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

    private Boolean inventoryReserved;

    public boolean canBeManagedBy(UserEntity actor) {
        if (actor == null || actor.getId() == null) {
            return false;
        }
        UserEntity manager = salesChannel == SalesChannel.POS ? createdBy : user;
        return manager != null && actor.getId().equals(manager.getId());
    }

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
