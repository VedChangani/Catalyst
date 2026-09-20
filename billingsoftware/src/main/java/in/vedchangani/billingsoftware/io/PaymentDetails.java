package in.vedchangani.billingsoftware.io;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentDetails {

    // One Razorpay order / captured payment can belong to at most one local order. Nullable, so
    // CASH orders (and UPI orders not yet sent to Razorpay) are unaffected.
    @Column(unique = true)
    private String razorpayOrderId;
    @Column(unique = true)
    private String razorpayPaymentId;
    private String razorpaySignature;
    private PaymentStatus status;
    // When backend signature verification moved the order to PAID. Null for CASH (never verified)
    // and for pending, failed and cancelled orders.
    private LocalDateTime paidAt;
    public enum PaymentStatus {
        PENDING, COMPLETED, FAILED
    }
}
