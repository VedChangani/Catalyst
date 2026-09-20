package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.AccountResponse;
import in.vedchangani.billingsoftware.io.AccountUpdateRequest;
import in.vedchangani.billingsoftware.io.AccountUpdateResponse;
import in.vedchangani.billingsoftware.io.PasswordChangeRequest;

/**
 * Self-service account operations for the AUTHENTICATED user. The account is always resolved from
 * the security context - no method takes a user id - so nobody can act on another account.
 */
public interface AccountService {

    AccountResponse getMyAccount();

    /** Updates name / email / mobile only. Role and status can never change here. */
    AccountUpdateResponse updateMyAccount(AccountUpdateRequest request);

    /** Verifies the current password, sets the new one and revokes every token the account holds. */
    void changeMyPassword(PasswordChangeRequest request);
}
