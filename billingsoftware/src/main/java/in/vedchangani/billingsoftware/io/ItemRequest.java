package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class ItemRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be greater than 0")
    // whole paise only: at most 2 decimals, so every order total is exact (see util/Money)
    @Digits(integer = 15, fraction = 2, message = "Price must have at most 2 decimal places")
    private BigDecimal price;

    @NotBlank(message = "categoryId is required")
    private String categoryId;

    private String description;

    private String sku;

    // Required for new items - never silently defaulted. An admin must always state the initial
    // stock level explicitly.
    @NotNull(message = "stockQuantity is required")
    @Min(value = 0, message = "stockQuantity must not be negative")
    private Integer stockQuantity;

    @Min(value = 0, message = "lowStockThreshold must not be negative")
    private Integer lowStockThreshold;

    // Optional - defaults to true at the service layer when omitted.
    private Boolean active;
}
