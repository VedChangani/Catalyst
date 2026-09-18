package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRequest {

    // The payment amount is never trusted from the client: the server looks it up from the
    // local order's authoritative grandTotal (see RazorpayServiceImpl.createOrder), keyed by
    // orderId. Only the local order identity and, optionally, the currency are accepted here.
    private String orderId;
    private String currency;
}
