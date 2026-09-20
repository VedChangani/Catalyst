package in.vedchangani.billingsoftware.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Uniform error body returned for every failed request. Deliberately carries only a status
 * code, a human-readable message, a timestamp and the request path - never a stack trace,
 * exception class name, or any underlying (SQL/JWT/Razorpay/AWS) error detail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private int status;
    private String error;
    private String message;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    private String path;
}
