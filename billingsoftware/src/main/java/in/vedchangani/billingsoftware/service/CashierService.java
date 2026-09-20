package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.CashierResponse;

import java.util.List;

/**
 * ADMIN cashier management. Every operation targets ROLE_CASHIER accounts only; a cashier is
 * deactivated/reactivated, never deleted, and its role can never be changed here.
 */
public interface CashierService {

    CashierResponse createCashier(CashierCreateRequest request);

    /** All cashiers (name order) with their POS sales metrics, computed in one grouped query. */
    List<CashierResponse> listCashiers();

    CashierResponse setEnabled(String cashierUserId, boolean enabled);

    void resetPassword(String cashierUserId, String newPassword);
}
