package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccountUpdateResponse {
    private AccountResponse account;
    private String token;
}
