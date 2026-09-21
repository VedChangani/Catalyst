package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.AuthRequest;
import in.vedchangani.billingsoftware.io.AuthResponse;
import in.vedchangani.billingsoftware.io.CustomerRegistrationRequest;
import in.vedchangani.billingsoftware.io.ForgotPasswordRequest;
import in.vedchangani.billingsoftware.io.MessageResponse;
import in.vedchangani.billingsoftware.io.PasswordResetRequest;
import in.vedchangani.billingsoftware.io.UserResponse;
import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.PasswordResetService;
import in.vedchangani.billingsoftware.service.UserService;
import in.vedchangani.billingsoftware.service.impl.AppUserDetailsService;
import in.vedchangani.billingsoftware.util.ClientIpResolver;
import in.vedchangani.billingsoftware.util.JwtUtil;
import in.vedchangani.billingsoftware.util.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;


@RestController
@RequiredArgsConstructor
public class AuthController {

    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AppUserDetailsService appUserDetailsService;
    private final UserService userService;
    private final AuditService auditService;

    private final JwtUtil jwtUtil;

    private final PasswordResetService passwordResetService;
    private final PasswordResetProperties passwordResetProperties;
    private final RequestRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    static final String FORGOT_PASSWORD_MESSAGE =
            "If an eligible account exists for that email, a verification code has been sent.";
    static final String RESET_SUCCESS_MESSAGE = "Password updated. Please sign in.";
    static final String TOO_MANY_REQUESTS_MESSAGE = "Too many requests. Please try again later.";


    // Public customer self-registration. The role is fixed to ROLE_USER by the service; staff
    // accounts are only ever created by an ADMIN through /admin/cashiers.
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody CustomerRegistrationRequest request) {
        return userService.registerCustomer(request);
    }

    // Customer forgot-password, step 1. ALWAYS answers 202 with the same body - unknown email,
    // cashier, admin, disabled account, resend cooldown and hourly cap included - so the response
    // reveals nothing about the account. Only a malformed email (400) or an exhausted per-IP
    // limit (429, which depends on the caller, never on the account) differs.
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                          HttpServletRequest httpRequest) {
        enforceIpLimit("forgot-password", httpRequest, passwordResetProperties.getRequestIpLimit());
        passwordResetService.requestReset(request.getEmail());
        return new MessageResponse(FORGOT_PASSWORD_MESSAGE);
    }

    // Customer forgot-password, step 2. Every problem with the code or the account is the same
    // 400 "The code is invalid or has expired." (never 401). No token is issued: the customer
    // signs in normally afterwards, and every earlier session is revoked (tokenVersion).
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody PasswordResetRequest request,
                                         HttpServletRequest httpRequest) {
        enforceIpLimit("reset-password", httpRequest, passwordResetProperties.getResetIpLimit());
        passwordResetService.resetPassword(request.getEmail(), request.getOtp(),
                request.getNewPassword(), request.getConfirmNewPassword());
        return new MessageResponse(RESET_SUCCESS_MESSAGE);
    }

    // Instance-local, in-memory limit per client IP (see RequestRateLimiter). The per-account
    // limits live in the database and never change the response.
    private void enforceIpLimit(String endpoint, HttpServletRequest httpRequest, int limit) {
        String key = endpoint + ":" + clientIpResolver.resolve(httpRequest);
        if (!rateLimiter.tryAcquire(key, limit, passwordResetProperties.getIpWindow())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_REQUESTS_MESSAGE);
        }
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest request) {
        // The identifier (email or mobile) is resolved to the account's stored email, which stays
        // the single username for authentication and the JWT subject. An unknown identifier still
        // goes through authenticate() so it fails exactly like a wrong password.
        String resolvedEmail = userService.resolveLoginEmail(request.getIdentifier());
        String username = resolvedEmail != null ? resolvedEmail : request.getIdentifier().trim();
        authenticate(username, request.getPassword());
        final UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        final String jwtToken = jwtUtil.generateToken(userDetails);
        String role = userService.getUserRole(username);
        // Only reached once authentication has succeeded; nothing about failed attempts is recorded
        // (an unauthenticated identifier must not be attributed to an account).
        auditService.recordLoginSuccess(username);
        return new AuthResponse(username, jwtToken, role);
    }

    private static final String GENERIC_LOGIN_FAILURE = "Email/mobile or password is incorrect";

    // Every unsuccessful login - unknown identifier, wrong password, or a deactivated account (even
    // with the right password) - gets the SAME status and message, so the response reveals neither
    // whether an account exists nor whether it is disabled. A disabled account is still rejected.
    private void authenticate(String email, String password) {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
        } catch (DisabledException e) {
            // Spring rejects a disabled account BEFORE comparing the password. Run one BCrypt
            // comparison anyway (result ignored) so this path takes about as long as a wrong-password
            // attempt and its timing does not single out disabled accounts either.
            passwordEncoder.matches(password, appUserDetailsService.loadUserByUsername(email).getPassword());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, GENERIC_LOGIN_FAILURE);
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, GENERIC_LOGIN_FAILURE);
        }
    }
}
