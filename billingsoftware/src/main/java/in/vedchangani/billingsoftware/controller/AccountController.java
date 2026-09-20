package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.AccountResponse;
import in.vedchangani.billingsoftware.io.AccountUpdateRequest;
import in.vedchangani.billingsoftware.io.AccountUpdateResponse;
import in.vedchangani.billingsoftware.io.PasswordChangeRequest;
import in.vedchangani.billingsoftware.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// The caller's OWN account (any authenticated role). "me" is the authenticated principal - there is
// deliberately no route that takes a user id.
@RestController
@RequiredArgsConstructor
@RequestMapping("/account/me")
public class AccountController {

    private final AccountService accountService;

    @GetMapping
    public AccountResponse getMyAccount() {
        return accountService.getMyAccount();
    }

    @PatchMapping
    public AccountUpdateResponse updateMyAccount(@Valid @RequestBody AccountUpdateRequest request) {
        return accountService.updateMyAccount(request);
    }

    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeMyPassword(@Valid @RequestBody PasswordChangeRequest request) {
        accountService.changeMyPassword(request);
    }
}
