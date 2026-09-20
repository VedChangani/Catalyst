package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
// Returned to the browser to open Razorpay Checkout. Contains only public data: never the key
// secret. keyId is the PUBLIC key id this Razorpay order was created with - the frontend opens
// Checkout with exactly this key, so the two can never disagree.
public class RazorpayOrderResponse {
    private String keyId;
    private String id;
    private String entity;
    private Integer amount;
    private String currency;
    private String status;
    private Date created_at;
    private String receipt;
}
