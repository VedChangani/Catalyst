package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.CustomerSummaryResponse;
import in.vedchangani.billingsoftware.io.UserRequest;
import in.vedchangani.billingsoftware.io.UserResponse;

import java.util.List;

public interface UserService {

    UserResponse createUser(UserRequest request);

    String getUserRole(String email);

    List<UserResponse> readUsers();

    void deleteUser(String id);

    /**
     * Read-only lookup of registered customers (ROLE_USER accounts) for a cashier/admin to
     * explicitly select one for a POS sale. Returns only the minimum needed to pick a customer.
     */
    List<CustomerSummaryResponse> searchCustomers(String search);
}
