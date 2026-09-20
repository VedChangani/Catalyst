package in.vedchangani.billingsoftware.config;

import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A8: developer-provisioned initial ADMIN. Each case builds an AdminBootstrap with its own
 * configuration against the real H2 database, and "restarts" by running it again.
 * The test profile itself has bootstrap disabled, so the context's own startup created nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest {

    private static final String BOOT_PASSWORD = "Boot5trapPass";

    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditService auditService;
    @Autowired private AdminBootstrapProperties contextProperties;
    @Autowired private MockMvc mockMvc;

    private String s;

    @BeforeEach
    void setUp() {
        // "no admin exists" must really mean none: start from an empty user table
        orderEntityRepository.deleteAll();
        userRepository.deleteAll();
        s = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        userRepository.deleteAll();
    }

    private AdminBootstrap bootstrap(boolean enabled, String name, String email, String mobile, String password) {
        return new AdminBootstrap(new AdminBootstrapProperties(enabled, name, email, mobile, password),
                userRepository, passwordEncoder, auditService);
    }

    private AdminBootstrap validBootstrap(String email, String mobile) {
        return bootstrap(true, "Root Admin", email, mobile, BOOT_PASSWORD);
    }

    private List<UserEntity> admins() {
        return userRepository.findByRoleOrderByNameAsc("ROLE_ADMIN");
    }

    private UserEntity account(String email, String role) {
        return userRepository.save(UserEntity.builder().userId("uid-" + UUID.randomUUID()).email(email)
                .name("Existing " + role).role(role).mobile(TestMobiles.next())
                .password(passwordEncoder.encode("Existing123")).build());
    }

    private int loginStatus(String identifier, String password) throws Exception {
        return mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void testProfile_hasBootstrapDisabled_andStartupCreatedNothing() {
        assertFalse(contextProperties.isEnabled());
        assertTrue(admins().isEmpty());
    }

    @Test
    void disabled_createsNothing_evenWithCredentialsConfigured() {
        assertFalse(bootstrap(false, "Root", "root-" + s + "@example.com", TestMobiles.next(), BOOT_PASSWORD).provision());
        assertTrue(admins().isEmpty());
    }

    @Test
    void enabled_createsExactlyOneHashedEnabledAdmin_andRepeatedStartupsAreNoOps(CapturedOutput output) throws Exception {
        String email = "root-" + s + "@example.com";
        String mobile = TestMobiles.next();
        AdminBootstrap bootstrap = validBootstrap(" Root-" + s + "@Example.com ", "+91 " + mobile);

        assertTrue(bootstrap.provision());
        assertFalse(bootstrap.provision());                                   // second start
        assertFalse(validBootstrap("other-" + s + "@example.com", TestMobiles.next()).provision()); // changed config

        List<UserEntity> admins = admins();
        assertEquals(1, admins.size());
        UserEntity admin = admins.get(0);
        assertEquals("ROLE_ADMIN", admin.getRole());
        assertEquals(email, admin.getEmail());
        assertEquals(mobile, admin.getMobile());
        assertEquals("Root Admin", admin.getName());
        assertTrue(admin.isAccountEnabled());
        assertEquals(0, admin.currentTokenVersion());
        assertNotEquals(BOOT_PASSWORD, admin.getPassword());
        assertTrue(admin.getPassword().startsWith("$2"));
        assertTrue(passwordEncoder.matches(BOOT_PASSWORD, admin.getPassword()));
        // the bootstrapped admin signs in normally, by email or mobile
        assertEquals(200, loginStatus(email, BOOT_PASSWORD));
        assertEquals(200, loginStatus(mobile, BOOT_PASSWORD));
        // the password never reached the logs
        assertFalse(output.getAll().contains(BOOT_PASSWORD));
    }

    @Test
    void existingAdmin_isNeverOverwritten_resetOrDuplicated() throws Exception {
        UserEntity existing = account("legacy-admin-" + s + "@example.com", "ROLE_ADMIN");
        String hashBefore = existing.getPassword();

        // same email as the existing admin but a different password and name
        assertFalse(bootstrap(true, "Someone Else", existing.getEmail(), TestMobiles.next(), BOOT_PASSWORD).provision());
        assertFalse(validBootstrap("new-admin-" + s + "@example.com", TestMobiles.next()).provision());

        List<UserEntity> admins = admins();
        assertEquals(1, admins.size());
        UserEntity after = admins.get(0);
        assertEquals(existing.getUserId(), after.getUserId());
        assertEquals(hashBefore, after.getPassword());
        assertEquals("Existing ROLE_ADMIN", after.getName());
        assertEquals(existing.getMobile(), after.getMobile());
        assertEquals(0, after.currentTokenVersion());
        assertEquals(200, loginStatus(existing.getEmail(), "Existing123"));
        assertEquals(401, loginStatus(existing.getEmail(), BOOT_PASSWORD));
    }

    @Test
    void collisionWithAnExistingCustomerOrCashier_failsStartup_andNeverPromotesIt() {
        UserEntity customer = account("taken-" + s + "@example.com", "ROLE_USER");
        UserEntity cashier = account("cashier-" + s + "@example.com", "ROLE_CASHIER");

        IllegalStateException byEmail = assertThrows(IllegalStateException.class,
                () -> validBootstrap(customer.getEmail().toUpperCase(), TestMobiles.next()).provision());
        IllegalStateException byMobile = assertThrows(IllegalStateException.class,
                () -> validBootstrap("fresh-" + s + "@example.com", cashier.getMobile()).provision());

        assertTrue(byEmail.getMessage().contains("refusing"));
        assertFalse(byEmail.getMessage().contains(BOOT_PASSWORD));
        assertFalse(byMobile.getMessage().contains(BOOT_PASSWORD));
        assertTrue(admins().isEmpty());
        UserEntity customerAfter = userRepository.findByUserId(customer.getUserId()).orElseThrow();
        assertEquals("ROLE_USER", customerAfter.getRole());
        assertEquals(customer.getPassword(), customerAfter.getPassword());
        assertEquals("ROLE_CASHIER", userRepository.findByUserId(cashier.getUserId()).orElseThrow().getRole());
    }

    @Test
    void enabledButIncompleteOrInvalidConfiguration_failsStartup_withoutDefaults() {
        String email = "root-" + s + "@example.com";
        String mobile = TestMobiles.next();
        List<AdminBootstrap> broken = List.of(
                bootstrap(true, "Root", email, mobile, null),
                bootstrap(true, "Root", email, mobile, "  "),
                bootstrap(true, null, email, mobile, BOOT_PASSWORD),
                bootstrap(true, "Root", null, mobile, BOOT_PASSWORD),
                bootstrap(true, "Root", email, "", BOOT_PASSWORD),
                bootstrap(true, "Root", "not-an-email", mobile, BOOT_PASSWORD),
                bootstrap(true, "Root", email, "12345", BOOT_PASSWORD),
                bootstrap(true, "Root", email, mobile, "admin"),
                bootstrap(true, "Root", email, mobile, "password"),
                bootstrap(true, "Root", email, mobile, "12345678"));
        for (AdminBootstrap b : broken) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, b::provision);
            assertTrue(ex.getMessage().startsWith("Admin bootstrap misconfigured"));
            assertFalse(ex.getMessage().contains(BOOT_PASSWORD));
        }
        assertTrue(admins().isEmpty());
        assertTrue(userRepository.findByEmail(email).isEmpty());
    }
}
