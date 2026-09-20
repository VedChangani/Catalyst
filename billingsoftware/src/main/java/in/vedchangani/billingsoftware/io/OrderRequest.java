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
// Request body for POST /orders (ONLINE). There is deliberately no customerName, phoneNumber, userId,
// createdBy or salesChannel field: the customer is always the authenticated ROLE_USER, and the order's
// customerName/phoneNumber are snapshotted from that account by the server (see OrderServiceImpl).
// Any such property a client sends anyway is ignored on deserialization.
public class OrderRequest {

    @NotBlank(message = "Payment method is required")
    private String paymentMethod;

    @NotEmpty(message = "Cart must not be empty")
    @Valid
    private List<OrderItemRequest> cartItems;

    // Item identity, name and price are never trusted from the client: only itemId + quantity
    // are accepted here. The server looks up the authoritative name/price from the item catalog
    // and computes subtotal/tax/grandTotal itself (see OrderServiceImpl).
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
