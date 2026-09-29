package in.vedchangani.billingsoftware.io;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderRequest {

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    @NotEmpty(message = "Cart must not be empty")
    @Valid
    private List<OrderItemRequest> cartItems;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class OrderItemRequest {

        @NotBlank(message = "itemId is required")
        private String itemId;

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than 0")
        private Integer quantity;
    }
}
