package in.vedchangani.billingsoftware.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.sql.Timestamp;

// Inventory fields (sku, stockQuantity, reservedQuantity, lowStockThreshold, active, version) are
// kept nullable for now - existing rows have no value yet. They are tightened to NOT NULL and
// backfilled in a later batch, per the approved inventory plan; do not rely on defaults here.

@Entity
@Table(name = "tbl_items")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String itemId;

    private String name;

    private BigDecimal price;

    private String description;

    @CreationTimestamp
    @Column(updatable = false)
    private Timestamp createdAt;
    @UpdateTimestamp
    private Timestamp updatedAt;

    private String imgUrl;
    @ManyToOne
    @JoinColumn(name = "category_id", nullable = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    private CategoryEntity category;

    // Unique index intentionally deferred to a later batch, after existing rows are backfilled.
    private String sku;

    private Integer stockQuantity;

    private Integer reservedQuantity;

    private Integer lowStockThreshold;

    private Boolean active;

    @Version
    private Long version;
}
