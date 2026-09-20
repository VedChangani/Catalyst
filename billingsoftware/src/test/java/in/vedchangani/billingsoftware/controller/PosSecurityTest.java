package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.OrderCreationResult;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PosOrderRequest;
import in.vedchangani.billingsoftware.service.ItemService;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * URL-level authorization for the retail roles, through the real SecurityConfig filter chain.
 * Services are mocked: this only proves who may reach which endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PosSecurityTest {

    private static final String POS_BODY =
            "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"ITEM1\",\"quantity\":1}]}";
    private static final String ONLINE_BODY =
            "{\"customerName\":\"A\",\"phoneNumber\":\"9999999999\",\"paymentMethod\":\"CASH\","
                    + "\"cartItems\":[{\"itemId\":\"ITEM1\",\"quantity\":1}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private ItemService itemService;

    private static OrderCreationResult created() {
        return new OrderCreationResult(OrderResponse.builder().orderId("ORD1").build(), false);
    }

    // ---- POS endpoints ----

    @Test
    @WithMockUser(roles = "USER")
    void user_cannotCreatePosOrder() throws Exception {
        mockMvc.perform(post("/pos/orders").contentType(MediaType.APPLICATION_JSON).content(POS_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_cannotUseCustomerLookup() throws Exception {
        mockMvc.perform(get("/pos/customers").param("search", "ab")).andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    @Test
    void anonymous_cannotReachPosEndpoints() throws Exception {
        mockMvc.perform(post("/pos/orders").contentType(MediaType.APPLICATION_JSON).content(POS_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/pos/customers").param("search", "ab")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void cashier_canCreatePosOrderAndLookUpCustomers() throws Exception {
        when(orderService.createPosOrder(any(), any())).thenReturn(created());
        when(userService.searchCustomers("ab")).thenReturn(List.of());

        mockMvc.perform(post("/pos/orders").contentType(MediaType.APPLICATION_JSON).content(POS_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/pos/customers").param("search", "ab")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_cannotCreatePosOrder_orUsePosCustomerLookup_orReadPosSales() throws Exception {
        mockMvc.perform(post("/pos/orders").contentType(MediaType.APPLICATION_JSON).content(POS_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/pos/customers").param("search", "ab")).andExpect(status().isForbidden());
        mockMvc.perform(get("/pos/sales")).andExpect(status().isForbidden());
        verifyNoInteractions(orderService, userService);
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void posRequest_hasNoClientControlledChannelOrCreator() throws Exception {
        when(orderService.createPosOrder(any(), any())).thenReturn(created());
        String body = "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"ITEM1\",\"quantity\":1}],"
                + "\"salesChannel\":\"ONLINE\",\"createdBy\":\"someone-else\",\"userId\":\"someone-else\"}";

        mockMvc.perform(post("/pos/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<PosOrderRequest> captor = ArgumentCaptor.forClass(PosOrderRequest.class);
        verify(orderService).createPosOrder(captor.capture(), isNull());
        // the only association the DTO can carry is the explicit customer selection - unset here
        assertNull(captor.getValue().getCustomerUserId());
        assertEquals("CASH", captor.getValue().getPaymentMethod());
    }

    // ---- CASHIER is not an admin ----

    @Test
    @WithMockUser(roles = "CASHIER")
    void cashier_isRefusedEveryAdminOnlyEndpoint() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n\",\"email\":\"n@example.com\",\"password\":\"secret1\",\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/users/abc")).andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/items/ITEM1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/admin/items/ITEM1/stock").contentType(MediaType.APPLICATION_JSON).content("{\"delta\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard")).andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/latest")).andExpect(status().isForbidden());
        // orders cannot be hard-deleted by anyone any more (A9): the route has no DELETE handler
        mockMvc.perform(delete("/orders/ORD1")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(userService, itemService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_cannotUseTheOnlineOrderEndpoint() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ONLINE_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void cashier_cannotUseTheOnlineOrderEndpoint() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ONLINE_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void cashier_canBrowseItemsForThePos() throws Exception {
        when(itemService.fetchItems()).thenReturn(List.of());

        mockMvc.perform(get("/items")).andExpect(status().isOk());
    }

    // ---- existing roles unchanged ----

    @Test
    @WithMockUser(roles = "USER")
    void user_stillCreatesOnlineOrders_andStaysOutOfAdmin() throws Exception {
        when(orderService.createOrder(any(), any())).thenReturn(created());

        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ONLINE_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/admin/users")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/cashiers")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_stillReachesAdminEndpoints() throws Exception {
        mockMvc.perform(get("/admin/cashiers")).andExpect(status().isOk());
    }
}
