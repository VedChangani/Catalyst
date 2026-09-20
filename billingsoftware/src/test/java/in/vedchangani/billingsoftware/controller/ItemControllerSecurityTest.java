package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.io.ItemResponse;
import in.vedchangani.billingsoftware.io.ItemUpdateRequest;
import in.vedchangani.billingsoftware.io.StockAdjustmentRequest;
import in.vedchangani.billingsoftware.service.ItemService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real SecurityConfig filter chain (not a mocked security context) to confirm the
 * new admin-only inventory endpoints are actually gated by ROLE_ADMIN, the same way every other
 * /admin/** endpoint already is - no new SecurityConfig matcher was needed for this batch, and
 * this test is what confirms that's actually true rather than assumed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ItemControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ItemService itemService;

    @Test
    @WithMockUser(roles = "USER")
    void updateItem_rejectsNonAdminUser() throws Exception {
        ItemUpdateRequest request = ItemUpdateRequest.builder().name("Updated name").build();

        mockMvc.perform(put("/admin/items/ITEM1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateItem_allowsAdminUser() throws Exception {
        ItemUpdateRequest request = ItemUpdateRequest.builder().name("Updated name").build();
        when(itemService.update(anyString(), any())).thenReturn(ItemResponse.builder().itemId("ITEM1").build());

        mockMvc.perform(put("/admin/items/ITEM1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void adjustStock_rejectsNonAdminUser() throws Exception {
        StockAdjustmentRequest request = StockAdjustmentRequest.builder().delta(5).build();

        mockMvc.perform(patch("/admin/items/ITEM1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adjustStock_allowsAdminUser() throws Exception {
        StockAdjustmentRequest request = StockAdjustmentRequest.builder().delta(5).build();
        when(itemService.adjustStock(anyString(), any())).thenReturn(ItemResponse.builder().itemId("ITEM1").build());

        mockMvc.perform(patch("/admin/items/ITEM1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }
}
