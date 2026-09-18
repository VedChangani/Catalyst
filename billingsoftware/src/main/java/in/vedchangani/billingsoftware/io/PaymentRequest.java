package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.NotBlank;
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
    @NotBlank(message = "orderId is required")
    private String orderId;

    // Ignored: every payment is created in INR (see RazorpayServiceImpl). Accepted so existing clients keep working.
    private String currency;
}
