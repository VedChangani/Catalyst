package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Result of PATCH /account/me. The JWT subject is the account's email, so when the email changes
// every token the caller holds (subject = old email) stops working; `token` is then a fresh one
// for the new email so the caller's session carries on. It is null when the email did not change.
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccountUpdateResponse {
    private AccountResponse account;
    private String token;
}
