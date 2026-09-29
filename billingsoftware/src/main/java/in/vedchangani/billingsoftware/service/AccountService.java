package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.AccountResponse;
import in.vedchangani.billingsoftware.io.AccountUpdateRequest;
import in.vedchangani.billingsoftware.io.AccountUpdateResponse;
import in.vedchangani.billingsoftware.io.PasswordChangeRequest;

public interface AccountService {

    AccountResponse getMyAccount();

    AccountUpdateResponse updateMyAccount(AccountUpdateRequest request);

    void changeMyPassword(PasswordChangeRequest request);
}
