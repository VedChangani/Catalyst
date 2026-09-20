package in.vedchangani.billingsoftware.config;

import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.util.ContactNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates the initial ADMIN at startup - and only in one narrow case:
 *
 *   bootstrap disabled (default)                  -> nothing happens
 *   enabled, but name/email/mobile/password invalid or missing
 *                                                 -> startup FAILS (no insecure fallback, no defaults)
 *   enabled, and any ROLE_ADMIN already exists     -> nothing happens (no overwrite, no password reset,
 *                                                    no profile change, so repeated starts are no-ops)
 *   enabled, no admin yet, but the email/mobile already belongs to another account
 *                                                 -> startup FAILS (never promotes or edits that account)
 *   enabled, no admin, no collision               -> exactly one enabled ROLE_ADMIN is created
 *
 * The password is only ever passed to the PasswordEncoder; it is never logged or stored in plain text.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[A-Za-z])(?=.*[0-9]).{8,72}$");

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        provision();
    }

    // Separate from run() so tests can exercise it directly. Returns true only if an admin was created.
    @Transactional
    public boolean provision() {
        if (!properties.isEnabled()) {
            log.info("Admin bootstrap is disabled; no admin account is provisioned at startup");
            return false;
        }
        // Validated whenever bootstrap is enabled, so a half-configured deployment fails loudly.
        String name = required(properties.getName(), "APP_ADMIN_NAME").trim();
        String email = ContactNormalizer.normalizeEmail(required(properties.getEmail(), "APP_ADMIN_EMAIL"));
        String mobile = ContactNormalizer.normalizeMobile(required(properties.getMobile(), "APP_ADMIN_MOBILE"));
        String password = required(properties.getPassword(), "APP_ADMIN_PASSWORD");
        if (name.length() > 100) {
            throw misconfigured("APP_ADMIN_NAME must be at most 100 characters");
        }
        if (!EMAIL.matcher(email).matches()) {
            throw misconfigured("APP_ADMIN_EMAIL is not a valid email address");
        }
        if (mobile == null) {
            throw misconfigured("APP_ADMIN_MOBILE is not a valid 10-digit Indian mobile number");
        }
        if (!PASSWORD_POLICY.matcher(password).matches()) {
            throw misconfigured("APP_ADMIN_PASSWORD must be 8-72 characters with at least one letter and one number");
        }

        if (userRepository.existsByRole(ADMIN_ROLE)) {
            log.info("Admin bootstrap: an admin account already exists; nothing was changed");
            return false;
        }
        // Never turn an existing customer/cashier into an admin, and never edit it.
        if (userRepository.findByEmail(email).isPresent() || userRepository.findByMobile(mobile).isPresent()) {
            throw misconfigured("the configured admin email or mobile already belongs to an existing non-admin account; "
                    + "refusing to modify or promote it - configure a different email/mobile");
        }

        UserEntity admin = userRepository.save(UserEntity.builder()
                .userId(UUID.randomUUID().toString())
                .name(name)
                .email(email)
                .mobile(mobile)
                .password(passwordEncoder.encode(password))
                .role(ADMIN_ROLE)
                .enabled(true)
                .build());
        auditService.recordSystem(AuditAction.ACCOUNT_REGISTERED, AuditTargetType.ACCOUNT, admin.getUserId(),
                Map.of("source", "ADMIN_BOOTSTRAP"));
        log.info("Admin bootstrap: created the initial admin account {}", admin.getUserId());
        return true;
    }

    private static String required(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw misconfigured(variable + " is required when APP_ADMIN_BOOTSTRAP_ENABLED=true");
        }
        return value;
    }

    private static IllegalStateException misconfigured(String reason) {
        return new IllegalStateException("Admin bootstrap misconfigured: " + reason);
    }
}
