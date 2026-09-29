package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.AuthRequest;
import in.vedchangani.billingsoftware.io.AuthResponse;
import in.vedchangani.billingsoftware.io.CustomerRegistrationRequest;
import in.vedchangani.billingsoftware.io.UserResponse;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.UserService;
import in.vedchangani.billingsoftware.service.impl.AppUserDetailsService;
import in.vedchangani.billingsoftware.util.JwtUtil;
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


    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody CustomerRegistrationRequest request) {
        return userService.registerCustomer(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest request) {
        String resolvedEmail = userService.resolveLoginEmail(request.getIdentifier());
        String username = resolvedEmail != null ? resolvedEmail : request.getIdentifier().trim();
        authenticate(username, request.getPassword());
        final UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        final String jwtToken = jwtUtil.generateToken(userDetails);
        String role = userService.getUserRole(username);
        auditService.recordLoginSuccess(username);
        return new AuthResponse(username, jwtToken, role);
    }

    private static final String GENERIC_LOGIN_FAILURE = "Email/mobile or password is incorrect";

    private void authenticate(String email, String password) {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
        } catch (DisabledException e) {
            passwordEncoder.matches(password, appUserDetailsService.loadUserByUsername(email).getPassword());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, GENERIC_LOGIN_FAILURE);
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, GENERIC_LOGIN_FAILURE);
        }
    }
}
