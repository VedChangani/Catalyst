package in.vedchangani.billingsoftware.io;

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

    private String customerName;
    private String phoneNumber;
    private String paymentMethod;
    private List<OrderItemRequest> cartItems;

    // Item identity, name and price are never trusted from the client: only itemId + quantity
    // are accepted here. The server looks up the authoritative name/price from the item catalog
    // and computes subtotal/tax/grandTotal itself (see OrderServiceImpl).
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class OrderItemRequest {
        private String itemId;
        private Integer quantity;
    }
}
