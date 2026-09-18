package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ItemResponse {
    private String itemId;
    private String name;
    private BigDecimal price;
    private String categoryId;
    private String description;
    private String categoryName;
    private String imgUrl;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    private String sku;
    private Integer stockQuantity;
    // Exposed alongside stockQuantity for admin inventory management (how much is held against
    // in-flight orders). Customer-facing UI only needs availableQuantity; nothing here is
    // sensitive enough to warrant a separate role-based response shape.
    private Integer reservedQuantity;
    // stockQuantity - reservedQuantity: what the storefront can actually sell right now.
    private Integer availableQuantity;
    private Integer lowStockThreshold;
    private Boolean active;
}
