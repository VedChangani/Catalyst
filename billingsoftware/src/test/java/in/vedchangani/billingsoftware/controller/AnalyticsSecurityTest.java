package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.AnalyticsResponse;
import in.vedchangani.billingsoftware.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /admin/analytics is covered by the existing ADMIN-only /admin/** rule. Service is mocked: this
 * proves who may reach the endpoint, through the real SecurityConfig filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_isAllowed() throws Exception {
        when(analyticsService.getAnalytics(any(), any(), any())).thenReturn(new AnalyticsResponse());

        mockMvc.perform(get("/admin/analytics")).andExpect(status().isOk());
        verify(analyticsService).getAnalytics(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void cashier_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/analytics")).andExpect(status().isForbidden());
        verifyNoInteractions(analyticsService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/analytics")).andExpect(status().isForbidden());
        verifyNoInteractions(analyticsService);
    }

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/analytics")).andExpect(status().isUnauthorized());
        verifyNoInteractions(analyticsService);
    }
}
