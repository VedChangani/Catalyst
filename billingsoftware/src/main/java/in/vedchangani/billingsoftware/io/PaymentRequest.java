package in.vedchangani.billingsoftware.io;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRequest {

    @NotBlank(message = "orderId is required")
    private String orderId;

    private String currency;
}
