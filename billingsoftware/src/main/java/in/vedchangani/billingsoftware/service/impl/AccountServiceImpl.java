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

        userRepository.findByEmail(email).filter(other -> !other.getId().equals(me.getId())).ifPresent(other -> {
            throw new ConflictException("An account with this email already exists");
        });
        if (mobile != null) {
            userRepository.findByMobile(mobile).filter(other -> !other.getId().equals(me.getId())).ifPresent(other -> {
                throw new ConflictException("An account with this mobile number already exists");
            });
        }

        boolean emailChanged = !email.equals(me.getEmail());
        List<String> changedFields = new ArrayList<>();
        if (!request.getName().trim().equals(me.getName())) changedFields.add("name");
        if (emailChanged) changedFields.add("email");
        if (!Objects.equals(mobile, me.getMobile())) changedFields.add("mobile");
        me.setName(request.getName().trim());
        me.setEmail(email);
        me.setMobile(mobile);
        if (emailChanged) {
            me.setTokenVersion(me.currentTokenVersion() + 1);
        }
        userRepository.saveAndFlush(me);
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
        userRepository.updatePasswordAndRevokeTokens(me.getId(), passwordEncoder.encode(request.getNewPassword()));
        auditService.recordFor(me, AuditAction.PASSWORD_CHANGED, AuditTargetType.ACCOUNT, me.getUserId(), Map.of());
    }

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
