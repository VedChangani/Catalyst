package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.CustomerRegistrationRequest;
import in.vedchangani.billingsoftware.io.CustomerSummaryResponse;
import in.vedchangani.billingsoftware.io.UserResponse;

import java.util.List;

public interface UserService {

    UserResponse registerCustomer(CustomerRegistrationRequest request);

    UserResponse createCashier(CashierCreateRequest request);

    String resolveLoginEmail(String identifier);

    String getUserRole(String email);

    List<CustomerSummaryResponse> searchCustomers(String search);
}
