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

// Request body for POST /pos/orders. There is deliberately no salesChannel, createdBy or
// user-object field: the channel comes from the endpoint and the creator from the authenticated
// principal. customerUserId only *selects* an existing registered customer; the backend resolves
// the actual UserEntity itself. customerName/phoneNumber are billing details for the receipt and
// are never used to find or associate an account.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PosOrderRequest {

    // The stable userId returned by GET /pos/customers. Omit (null) for a walk-in sale.
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
