package in.vedchangani.billingsoftware.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * A9: CORS origins come from configuration (app.cors.allowed-origins / APP_CORS_ALLOWED_ORIGINS,
 * default http://localhost:5173 for local development) and are always explicit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigurationTest {

    @Autowired private MockMvc mockMvc;

    private MvcResult preflight(String origin) throws Exception {
        return mockMvc.perform(options("/login")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")).andReturn();
    }

    @Test
    void theConfiguredDevelopmentOrigin_isAllowed_withCredentials() throws Exception {
        MvcResult result = preflight("http://localhost:5173");

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("http://localhost:5173", result.getResponse().getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", result.getResponse().getHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    void anyOtherOrigin_isRejected() throws Exception {
        MvcResult result = preflight("https://evil.example");

        assertEquals(403, result.getResponse().getStatus());
        assertNull(result.getResponse().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void originConfiguration_isValidated_andWildcardsAreRefused() {
        assertEquals(List.of("https://shop.example", "http://localhost:5173"),
                SecurityConfig.validatedOrigins(List.of(" https://shop.example ", "", "http://localhost:5173")));
        assertThrows(IllegalStateException.class, () -> SecurityConfig.validatedOrigins(List.of("*")));
        assertThrows(IllegalStateException.class, () -> SecurityConfig.validatedOrigins(List.of("https://*.example")));
        assertThrows(IllegalStateException.class, () -> SecurityConfig.validatedOrigins(List.of(" ")));
        assertThrows(IllegalStateException.class, () -> SecurityConfig.validatedOrigins(null));
    }
}
