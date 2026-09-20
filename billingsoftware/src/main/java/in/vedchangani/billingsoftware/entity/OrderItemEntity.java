package in.vedchangani.billingsoftware.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Entity
@Table(name = "tbl_order_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String itemId;
    private String name;
    // Unit-price snapshot at order time (never re-read from the catalog). Same money type/column
    // definition as the order totals.
    @Column(precision = 19, scale = 4)
    private BigDecimal price;
    private Integer quantity;
}
