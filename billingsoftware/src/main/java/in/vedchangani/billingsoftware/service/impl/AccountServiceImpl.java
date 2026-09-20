package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.AccountResponse;
import in.vedchangani.billingsoftware.io.AccountUpdateRequest;
import in.vedchangani.billingsoftware.io.AccountUpdateResponse;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.PasswordChangeRequest;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AccountService;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.util.ContactNormalizer;
import in.vedchangani.billingsoftware.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Profile fields live on UserEntity only. Existing orders keep the customerName/phoneNumber they
// were created with (a historical snapshot): nothing here reads or writes orders.
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppUserDetailsService appUserDetailsService;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getMyAccount() {
        return toResponse(currentUser());
    }

    @Override
    @Transactional
    public AccountUpdateResponse updateMyAccount(AccountUpdateRequest request) {
        UserEntity me = currentUser();

        String email = ContactNormalizer.normalizeEmail(request.getEmail());
        String mobile = normalizedMobileFor(me, request.getMobile());

        // Uniqueness against OTHER accounts, on the same normalized values login uses. The unique
        // indexes still back this up for a concurrent race (reported as a generic 409).
        userRepository.findByEmail(email).filter(other -> !other.getId().equals(me.getId())).ifPresent(other -> {
            throw new ConflictException("An account with this email already exists");
        });
        if (mobile != null) {
            userRepository.findByMobile(mobile).filter(other -> !other.getId().equals(me.getId())).ifPresent(other -> {
                throw new ConflictException("An account with this mobile number already exists");
            });
        }

        boolean emailChanged = !email.equals(me.getEmail());
        // Only the NAMES of the changed fields are audited - never the old/new values.
        List<String> changedFields = new ArrayList<>();
        if (!request.getName().trim().equals(me.getName())) changedFields.add("name");
        if (emailChanged) changedFields.add("email");
        if (!Objects.equals(mobile, me.getMobile())) changedFields.add("mobile");
        me.setName(request.getName().trim());
        me.setEmail(email);
        me.setMobile(mobile);
        if (emailChanged) {
            // The JWT subject is the email, so tokens issued for the old email are revoked; the
            // caller gets a fresh token below and stays signed in.
            me.setTokenVersion(me.currentTokenVersion() + 1);
        }
        userRepository.saveAndFlush(me);
        // A save that changed nothing is not an event.
        if (!changedFields.isEmpty()) {
            auditService.recordFor(me, AuditAction.PROFILE_UPDATED, AuditTargetType.ACCOUNT, me.getUserId(),
                    Map.of("changedFields", changedFields));
        }

        String token = emailChanged ? jwtUtil.generateToken(appUserDetailsService.loadUserByUsername(email)) : null;
        return new AccountUpdateResponse(toResponse(me), token);
    }

    @Override
    @Transactional
    public void changeMyPassword(PasswordChangeRequest request) {
        UserEntity me = currentUser();
        if (!request.getNewPassword().equals(request.getConfirmNewPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), me.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (passwordEncoder.matches(request.getNewPassword(), me.getPassword())) {
            throw new IllegalArgumentException("New password must be different from the current password");
        }
        // hash + token-version bump in one statement: every session from before the change ends
        userRepository.updatePasswordAndRevokeTokens(me.getId(), passwordEncoder.encode(request.getNewPassword()));
        // No password material or token version in the event - only that it happened.
        auditService.recordFor(me, AuditAction.PASSWORD_CHANGED, AuditTargetType.ACCOUNT, me.getUserId(), Map.of());
    }

    // A blank mobile is only acceptable for an account that has none yet; otherwise the account
    // could not be used for login-by-mobile or checkout any more.
    private String normalizedMobileFor(UserEntity me, String rawMobile) {
        if (rawMobile == null || rawMobile.isBlank()) {
            if (me.getMobile() != null && !me.getMobile().isBlank()) {
                throw new IllegalArgumentException("mobile: Mobile is required");
            }
            return null;
        }
        String mobile = ContactNormalizer.normalizeMobile(rawMobile);
        if (mobile == null) {
            throw new IllegalArgumentException("mobile: Mobile must be a valid 10-digit Indian mobile number");
        }
        return mobile;
    }

    // The account is ALWAYS the authenticated principal's, resolved by the same mechanism the rest
    // of the application uses (security context -> email -> UserEntity).
    private UserEntity currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("No authenticated user found");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }

    private AccountResponse toResponse(UserEntity user) {
        return AccountResponse.builder()
                .name(user.getName())
                .email(user.getEmail())
                .mobile(user.getMobile())
                .role(user.getRole())
                .enabled(user.isAccountEnabled())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
