package in.vedchangani.billingsoftware.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * The deployed frontend origin, configured the way Railway does it (APP_CORS_ALLOWED_ORIGINS ->
 * app.cors.allowed-origins), and the real /api/v1.0 context path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.cors.allowed-origins=https://catalyst-dun-two.vercel.app")
class ProductionCorsPreflightTest {

    private static final String VERCEL = "https://catalyst-dun-two.vercel.app";

    @Autowired private MockMvc mockMvc;

    private MvcResult preflight(String origin, String method) throws Exception {
        return mockMvc.perform(options("/api/v1.0/login").contextPath("/api/v1.0")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "content-type")).andReturn();
    }

    @Test
    void loginPreflight_fromVercelOrigin_succeedsWithCorsHeaders() throws Exception {
        MvcResult r = preflight(VERCEL, "POST");
        assertEquals(200, r.getResponse().getStatus());
        assertEquals(VERCEL, r.getResponse().getHeader("Access-Control-Allow-Origin"));
        assertTrue(r.getResponse().getHeader("Access-Control-Allow-Methods").contains("POST"));
        assertEquals("true", r.getResponse().getHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    void loginPreflight_fromOtherOrigin_isRejected() throws Exception {
        MvcResult r = preflight("https://evil.example", "POST");
        assertEquals(403, r.getResponse().getStatus());
        assertNull(r.getResponse().getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void protectedEndpoints_stillRequireAuthentication() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/v1.0/admin/orders").contextPath("/api/v1.0")
                .header("Origin", VERCEL)).andReturn().getResponse().getStatus());
    }
}
