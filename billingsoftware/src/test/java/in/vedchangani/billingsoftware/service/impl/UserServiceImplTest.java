package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.io.CustomerRegistrationRequest;
import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.UserResponse;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Focused tests for UserServiceImpl account creation: registering an email that is already taken is a
 * business conflict (409), not an unhandled 500 or a silently-created duplicate account.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private AuditService auditService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserServiceImpl userService;

    @Test
    void createCashier_rejectsDuplicateEmail() {
        userService = new UserServiceImpl(userRepository, passwordEncoder, auditService);
        when(userRepository.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(new UserEntity()));

        CashierCreateRequest request = CashierCreateRequest.builder()
                .name("Alice")
                .email("Alice@Example.com")
                .mobile("9876543210")
                .password("password123")
                .build();

        assertThrows(ConflictException.class, () -> userService.createCashier(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createCashier_alwaysCreatesAnEnabledCashier() {
        userService = new UserServiceImpl(userRepository, passwordEncoder, auditService);
        when(userRepository.findByEmail("carl@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByMobile("9876543210")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.createCashier(CashierCreateRequest.builder()
                .name("Carl").email("carl@example.com").mobile("9876543210").password("password123").build());

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(saved.capture());
        assertEquals("ROLE_CASHIER", saved.getValue().getRole());
        assertEquals(Boolean.TRUE, saved.getValue().getEnabled());
        assertEquals(0, saved.getValue().currentTokenVersion());
        assertEquals("$2a$10$hash", saved.getValue().getPassword());
    }

    private static CustomerRegistrationRequest registration(String mobile) {
        return CustomerRegistrationRequest.builder()
                .name("Alice").email("Alice@Example.com").mobile(mobile).password("password123").build();
    }

    @Test
    void registerCustomer_alwaysCreatesRoleUser_withEncodedPasswordAndNormalizedIdentifiers() {
        userService = new UserServiceImpl(userRepository, passwordEncoder, auditService);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByMobile("9876543210")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.registerCustomer(registration("+91 98765 43210"));

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(saved.capture());
        assertEquals("ROLE_USER", saved.getValue().getRole());
        assertEquals("$2a$10$hash", saved.getValue().getPassword());
        assertEquals("alice@example.com", saved.getValue().getEmail());
        assertEquals("9876543210", saved.getValue().getMobile());
        assertEquals("ROLE_USER", response.getRole());
    }

    @Test
    void registerCustomer_rejectsDuplicateMobile() {
        userService = new UserServiceImpl(userRepository, passwordEncoder, auditService);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByMobile("9876543210")).thenReturn(Optional.of(new UserEntity()));

        assertThrows(ConflictException.class, () -> userService.registerCustomer(registration("9876543210")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerCustomer_rejectsInvalidMobile() {
        userService = new UserServiceImpl(userRepository, passwordEncoder, auditService);

        assertThrows(IllegalArgumentException.class, () -> userService.registerCustomer(registration("12345")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void resolveLoginEmail_matchesOnlyByNormalizedEmailOrMobile() {
        userService = new UserServiceImpl(userRepository, passwordEncoder, auditService);
        UserEntity alice = UserEntity.builder().email("alice@example.com").mobile("9876543210").build();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(userRepository.findByMobile("9876543210")).thenReturn(Optional.of(alice));

        assertEquals("alice@example.com", userService.resolveLoginEmail(" Alice@Example.com "));
        assertEquals("alice@example.com", userService.resolveLoginEmail("098765-43210"));
        // a name (or anything that is neither an email nor a valid mobile) never resolves
        assertNull(userService.resolveLoginEmail("Alice"));
        assertNull(userService.resolveLoginEmail(" "));
    }
}
