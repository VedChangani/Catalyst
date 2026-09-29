package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.CustomerRegistrationRequest;
import in.vedchangani.billingsoftware.io.CustomerSummaryResponse;
import in.vedchangani.billingsoftware.io.UserResponse;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.UserService;
import in.vedchangani.billingsoftware.util.ContactNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    private static final String CUSTOMER_ROLE = "ROLE_USER";
    private static final String CASHIER_ROLE = "ROLE_CASHIER";

    @Override
    @Transactional
    public UserResponse registerCustomer(CustomerRegistrationRequest request) {
        UserEntity customer = createAccount(request.getName(), request.getEmail(), request.getMobile(),
                request.getPassword(), CUSTOMER_ROLE);
        auditService.recordFor(customer, AuditAction.ACCOUNT_REGISTERED, AuditTargetType.ACCOUNT,
                customer.getUserId(), Map.of());
        return convertToResponse(customer);
    }

    @Override
    public UserResponse createCashier(CashierCreateRequest request) {
        return convertToResponse(createAccount(request.getName(), request.getEmail(), request.getMobile(),
                request.getPassword(), CASHIER_ROLE));
    }

    private UserEntity createAccount(String name, String rawEmail, String rawMobile, String rawPassword, String role) {
        String email = ContactNormalizer.normalizeEmail(rawEmail);
        String mobile = ContactNormalizer.normalizeMobile(rawMobile);
        if (mobile == null) {
            throw new IllegalArgumentException("mobile: Mobile must be a valid 10-digit Indian mobile number");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("An account with this email already exists");
        }
        if (userRepository.findByMobile(mobile).isPresent()) {
            throw new ConflictException("An account with this mobile number already exists");
        }
        UserEntity account = UserEntity.builder()
                .userId(UUID.randomUUID().toString())
                .name(name.trim())
                .email(email)
                .mobile(mobile)
                .password(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .build();
        return userRepository.save(account);
    }

    @Override
    public String resolveLoginEmail(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String trimmed = identifier.trim();
        if (trimmed.contains("@")) {
            String email = ContactNormalizer.normalizeEmail(trimmed);
            Optional<UserEntity> user = userRepository.findByEmail(email);
            if (user.isEmpty() && !email.equals(trimmed)) {
                user = userRepository.findByEmail(trimmed);
            }
            return user.map(UserEntity::getEmail).orElse(null);
        }
        String mobile = ContactNormalizer.normalizeMobile(trimmed);
        if (mobile == null) {
            return null;
        }
        return userRepository.findByMobile(mobile).map(UserEntity::getEmail).orElse(null);
    }

    private UserResponse convertToResponse(UserEntity newUser) {
        return UserResponse.builder()
                .name(newUser.getName())
                .email(newUser.getEmail())
                .mobile(newUser.getMobile())
                .userId(newUser.getUserId())
                .createdAt(newUser.getCreatedAt())
                .updatedAt(newUser.getUpdatedAt())
                .role(newUser.getRole())
                .build();
    }

    @Override
    public String getUserRole(String email) {
        UserEntity existingUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found for the email: "+email));
        return existingUser.getRole();
    }

    private static final int MIN_CUSTOMER_SEARCH_LENGTH = 2;
    private static final int MAX_CUSTOMER_SEARCH_RESULTS = 20;

    @Override
    public List<CustomerSummaryResponse> searchCustomers(String search) {
        String term = search == null ? "" : search.trim();
        if (term.length() < MIN_CUSTOMER_SEARCH_LENGTH) {
            throw new IllegalArgumentException(
                    "Search must be at least " + MIN_CUSTOMER_SEARCH_LENGTH + " characters");
        }
        String escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return userRepository.searchCustomers(escaped, PageRequest.of(0, MAX_CUSTOMER_SEARCH_RESULTS))
                .stream()
                .map(user -> CustomerSummaryResponse.builder()
                        .userId(user.getUserId())
                        .name(user.getName())
                        .email(user.getEmail())
                        .build())
                .collect(Collectors.toList());
    }
}
