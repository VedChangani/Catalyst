package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OrderCreationResult {
    private OrderResponse order;
    private boolean replayed;
}
