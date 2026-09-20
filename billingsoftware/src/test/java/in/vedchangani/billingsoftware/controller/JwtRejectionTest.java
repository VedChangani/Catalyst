package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A request only ever authenticates with a token this server issued, that has not expired, whose
 * subject is a live account, and whose account state still matches (that last part - password reset,
 * deactivation - is covered by SecurityHardeningTest). Every other kind of bearer token must simply
 * be "not authenticated": a 401 in the standard error body, never a 500 and never somebody's
 * identity. Runs through the real JWT filter, security chain and controllers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtRejectionTest {

    private static final String PASSWORD = "Secret123";
    private static final String PROTECTED_URL = "/account/me";

    @Value("${jwt.secret.key}") private String jwtSecret;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String s;
    private UserEntity customer;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        customer = account("Jwt Customer", "jwt-customer-" + s + "@example.com", "ROLE_USER");
        admin = account("Jwt Admin", "jwt-admin-" + s + "@example.com", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll(List.of(customer, admin));
    }

    private UserEntity account(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).name(name).role(role)
                .mobile(TestMobiles.next()).password(passwordEncoder.encode(PASSWORD)).build());
    }

    private String login(UserEntity account) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + account.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private MvcResult callWithHeader(String authorization) throws Exception {
        return mockMvc.perform(get(PROTECTED_URL).header("Authorization", authorization)).andReturn();
    }

    // a token this server would accept, except for the one property under test
    private String forgedToken(String subject, Date expiry, String secret) {
        return Jwts.builder().claim("tokenVersion", 0).setSubject(subject).setIssuedAt(new Date())
                .setExpiration(expiry).signWith(SignatureAlgorithm.HS256, secret).compact();
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void aRealLoginToken_authenticatesTheAccountItWasIssuedFor() throws Exception {
        MvcResult result = callWithHeader("Bearer " + login(customer));

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(customer.getEmail(), objectMapper.readTree(result.getResponse().getContentAsString()).get("email").asText());
    }

    @Test
    void everyKindOfInvalidToken_isAnswered401InTheStandardBody_andNeverAuthenticates() throws Exception {
        String real = login(customer);
        String[] parts = real.split("\\.");
        Date future = new Date(System.currentTimeMillis() + 3_600_000);

        // signature byte flipped (first character: the last one only carries padding bits)
        char flipped = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String badSignature = parts[0] + "." + parts[1] + "." + flipped + parts[2].substring(1);

        // privilege escalation attempt: a customer's own token with the subject rewritten to the admin's
        JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        Map<String, Object> claims = new LinkedHashMap<>();
        payload.fields().forEachRemaining(e -> claims.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()));
        claims.put("sub", admin.getEmail());
        String rewrittenSubject = parts[0] + "."
                + base64Url(objectMapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        // unsigned token ("alg":"none") naming the admin
        String unsigned = base64Url("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(("{\"sub\":\"" + admin.getEmail() + "\",\"tokenVersion\":0}").getBytes(StandardCharsets.UTF_8)) + ".";

        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("not a jwt at all", "not-a-jwt");
        invalid.put("empty token", "");
        invalid.put("tampered signature", badSignature);
        invalid.put("subject rewritten to the admin, original signature kept", rewrittenSubject);
        invalid.put("unsigned (alg none) token for the admin", unsigned);
        invalid.put("signed with a different secret", forgedToken(admin.getEmail(), future, "some-other-secret-value"));
        invalid.put("expired", forgedToken(customer.getEmail(), new Date(System.currentTimeMillis() - 60_000), jwtSecret));
        invalid.put("valid signature but no such account", forgedToken("nobody-" + s + "@example.com", future, jwtSecret));

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> attempt : invalid.entrySet()) {
            MvcResult result = callWithHeader("Bearer " + attempt.getValue());
            int status = result.getResponse().getStatus();
            if (status != 401) {
                problems.add(attempt.getKey() + " -> " + status + " (expected 401)");
                continue;
            }
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            if (body.path("status").asInt() != 401 || !body.hasNonNull("message") || !body.hasNonNull("path")
                    || result.getResponse().getContentAsString().contains("Exception")) {
                problems.add(attempt.getKey() + " -> not the standard error body: " + result.getResponse().getContentAsString());
            }
        }

        assertTrue(problems.isEmpty(), "invalid tokens must be rejected cleanly:\n" + String.join("\n", problems));
    }

    @Test
    void aValidTokenOutsideTheBearerScheme_isIgnored() throws Exception {
        String real = login(customer);

        assertEquals(401, callWithHeader(real).getResponse().getStatus());
        assertEquals(401, callWithHeader("Basic " + real).getResponse().getStatus());
        assertFalse(callWithHeader("Bearer " + real).getResponse().getStatus() == 401, "control: the same token works as a Bearer token");
    }
}
