package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.CustomerRegistrationRequest;
import in.vedchangani.billingsoftware.io.CustomerSummaryResponse;
import in.vedchangani.billingsoftware.io.UserResponse;

import java.util.List;

public interface UserService {

    /**
     * Public customer self-registration. Always creates a ROLE_USER account; the caller can never
     * choose the role. Rejects an email or mobile that already belongs to an account.
     */
    UserResponse registerCustomer(CustomerRegistrationRequest request);

    /** ADMIN cashier creation: always an enabled ROLE_CASHIER; same validation as registration. */
    UserResponse createCashier(CashierCreateRequest request);

    /**
     * Resolves a login identifier (email or mobile, in any accepted format) to the account's
     * stored email - the username used by authentication and the JWT subject. Returns null when
     * no account matches, so the caller can fail with the same generic error either way.
     */
    String resolveLoginEmail(String identifier);

    String getUserRole(String email);

    /**
     * Read-only lookup of registered customers (ROLE_USER accounts) for a cashier to
     * explicitly select one for a POS sale. Returns only the minimum needed to pick a customer.
     */
    List<CustomerSummaryResponse> searchCustomers(String search);
}
