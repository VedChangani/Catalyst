package in.vedchangani.billingsoftware.io;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PosOrderRequest {

    private String customerUserId;

    private String customerName;

    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phoneNumber;

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    @NotEmpty(message = "Cart must not be empty")
    @Valid
    private List<OrderRequest.OrderItemRequest> cartItems;
}
