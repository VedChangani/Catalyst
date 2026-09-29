package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ItemUpdateRequest {

    private String name;

    @Positive(message = "Price must be greater than 0")
    @Digits(integer = 15, fraction = 2, message = "Price must have at most 2 decimal places")
    private BigDecimal price;

    private String categoryId;

    private String description;

    private String sku;

    @Min(value = 0, message = "lowStockThreshold must not be negative")
    private Integer lowStockThreshold;

    private Boolean active;
}
