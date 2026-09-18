package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Minimum a cashier needs to pick an existing registered customer. No password, role or
// timestamps.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CustomerSummaryResponse {
    private String userId;
    private String name;
    private String email;
}
