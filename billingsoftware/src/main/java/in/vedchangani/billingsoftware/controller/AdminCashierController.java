package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.CashierPasswordResetRequest;
import in.vedchangani.billingsoftware.io.CashierResponse;
import in.vedchangani.billingsoftware.io.CashierStatusRequest;
import in.vedchangani.billingsoftware.service.CashierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// ADMIN only: /admin/** is gated in SecurityConfig. There is deliberately no DELETE: cashiers are
// deactivated, never removed, so their historical POS orders keep a valid createdBy.
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/cashiers")
public class AdminCashierController {

    private final CashierService cashierService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CashierResponse createCashier(@Valid @RequestBody CashierCreateRequest request) {
        return cashierService.createCashier(request);
    }

    @GetMapping
    public List<CashierResponse> listCashiers() {
        return cashierService.listCashiers();
    }

    @PatchMapping("/{cashierId}/status")
    public CashierResponse updateStatus(@PathVariable String cashierId,
                                        @Valid @RequestBody CashierStatusRequest request) {
        return cashierService.setEnabled(cashierId, request.getEnabled());
    }

    @PostMapping("/{cashierId}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String cashierId,
                              @Valid @RequestBody CashierPasswordResetRequest request) {
        cashierService.resetPassword(cashierId, request.getPassword());
    }
}
