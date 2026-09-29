package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.CashierResponse;

import java.util.List;

public interface CashierService {

    CashierResponse createCashier(CashierCreateRequest request);

    List<CashierResponse> listCashiers();

    CashierResponse setEnabled(String cashierUserId, boolean enabled);

    void resetPassword(String cashierUserId, String newPassword);
}
