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

    @Column(unique = true)
    private String razorpayOrderId;
    @Column(unique = true)
    private String razorpayPaymentId;
    private String razorpaySignature;
    private PaymentStatus status;
    private LocalDateTime paidAt;
    public enum PaymentStatus {
        PENDING, COMPLETED, FAILED
    }
}
