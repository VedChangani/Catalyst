package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Data;

// Internal service -> controller carrier (never serialised): the order plus whether it was
// returned by replaying an Idempotency-Key instead of being created by this call.
@Data
@AllArgsConstructor
public class OrderCreationResult {
    private OrderResponse order;
    private boolean replayed;
}
