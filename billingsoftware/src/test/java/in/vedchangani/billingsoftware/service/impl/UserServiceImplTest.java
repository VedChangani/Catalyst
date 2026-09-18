package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.io.UserRequest;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Focused test for UserServiceImpl.createUser: registering an email that is already taken is a
 * business conflict (409), not an unhandled 500 or a silently-created duplicate account.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserServiceImpl userService;

    @Test
    void createUser_rejectsDuplicateEmail() {
        userService = new UserServiceImpl(userRepository, passwordEncoder);
        when(userRepository.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(new UserEntity()));

        UserRequest request = UserRequest.builder()
                .name("Alice")
                .email("alice@example.com")
                .password("password123")
                .role("ROLE_USER")
                .build();

        assertThrows(ConflictException.class, () -> userService.createUser(request));
        verify(userRepository, never()).save(any());
    }
}
