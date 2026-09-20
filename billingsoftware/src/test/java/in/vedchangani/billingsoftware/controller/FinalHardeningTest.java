package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A8 final hardening, on the real security chain / JWT filter / services / H2:
 *  - POST /encode no longer exists
 *  - every failed login (unknown, wrong password, disabled) gets one identical response
 *  - GET /orders/my-orders is ROLE_USER only
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinalHardeningTest {

    private static final String PASSWORD = "Secret123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String s;
    private UserEntity admin;
    private UserEntity cashier;
    private UserEntity customerA;
    private UserEntity customerB;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        admin = account("Ada Admin", "ada-" + s + "@example.com", "ROLE_ADMIN");
        cashier = account("Casey Cashier", "casey-" + s + "@example.com", "ROLE_CASHIER");
        customerA = account("Aaron Customer", "aaron-" + s + "@example.com", "ROLE_USER");
        customerB = account("Bella Customer", "bella-" + s + "@example.com", "ROLE_USER");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("fh-cat-" + s).name("Hardening " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("fh-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
                .category(category).stockQuantity(100).reservedQuantity(0)
                .lowStockThreshold(5).active(true).build());
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UserEntity account(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).name(name).role(role)
                .mobile(TestMobiles.next()).password(passwordEncoder.encode(PASSWORD)).build());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserEntity actor) {
        return request.with(user(actor.getEmail()).roles(actor.getRole().replace("ROLE_", "")));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult login(String identifier, String password) throws Exception {
        return perform(json(post("/login"), "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}"));
    }

    private String onlineOrder(UserEntity customer) throws Exception {
        MvcResult result = perform(json(as(post("/orders"), customer),
                "{\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    private String posSale(UserEntity linkedCustomer) throws Exception {
        String customerPart = linkedCustomer == null ? "" : "\"customerUserId\":\"" + linkedCustomer.getUserId() + "\",";
        MvcResult result = perform(json(as(post("/pos/orders"), cashier), "{" + customerPart
                + "\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":1}]}"));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return body(result).get("orderId").asText();
    }

    // ---- /encode removed ----

    @Test
    void encodeEndpoint_isGone_andNoLongerPublic() throws Exception {
        String body = "{\"password\":\"anything123\"}";
        // anonymous: no public route any more, so it is simply an unauthenticated request
        MvcResult anonymous = perform(json(post("/encode"), body));
        assertEquals(401, anonymous.getResponse().getStatus());
        assertFalse(anonymous.getResponse().getContentAsString().contains("$2"));
        // even an authenticated admin finds no such endpoint: 404 in the standard error format
        MvcResult asAdmin = perform(json(as(post("/encode"), admin), body));
        assertEquals(404, asAdmin.getResponse().getStatus());
        assertEquals("Resource not found", body(asAdmin).get("message").asText());
        assertFalse(asAdmin.getResponse().getContentAsString().contains("$2"));
        // the internal PasswordEncoder still works (logins below depend on it)
        assertTrue(passwordEncoder.matches(PASSWORD, userRepository.findById(customerA.getId()).orElseThrow().getPassword()));
        assertEquals(200, login(customerA.getEmail(), PASSWORD).getResponse().getStatus());
    }

    // ---- identical login failures ----

    @Test
    void unknownWrongPasswordAndDisabled_allGetTheSameResponse() throws Exception {
        UserEntity disabled = account("Dee Disabled", "dee-" + s + "@example.com", "ROLE_CASHIER");
        disabled.setEnabled(false);
        userRepository.save(disabled);

        List<MvcResult> failures = List.of(
                login("nobody-" + s + "@example.com", PASSWORD),   // unknown email
                login(TestMobiles.next(), PASSWORD),                 // unknown mobile
                login(customerA.getEmail(), "Wrong12345"),          // wrong password
                login(disabled.getEmail(), PASSWORD),               // disabled, RIGHT password
                login(disabled.getMobile(), PASSWORD),              // disabled, by mobile
                login(disabled.getEmail(), "Wrong12345"));          // disabled, wrong password

        List<String> shapes = new ArrayList<>();
        for (MvcResult failure : failures) {
            assertEquals(401, failure.getResponse().getStatus());
            JsonNode error = body(failure);
            assertEquals("Email/mobile or password is incorrect", error.get("message").asText());
            assertFalse(error.has("token"));
            String raw = failure.getResponse().getContentAsString().toLowerCase();
            assertFalse(raw.contains("disabled"));
            assertFalse(raw.contains("deactivat"));
            assertFalse(raw.contains("inactive"));
            // identical payload apart from the timestamp
            List<String> fields = new ArrayList<>();
            error.fieldNames().forEachRemaining(fields::add);
            shapes.add(fields + "|" + error.get("status") + "|" + error.get("error") + "|" + error.get("message")
                    + "|" + error.get("path"));
        }
        assertEquals(1, shapes.stream().distinct().count(), shapes.toString());
    }

    @Test
    void disabledAccount_stillCannotAuthenticate_andEnabledOnesStillCan() throws Exception {
        UserEntity disabled = account("Dee Disabled", "dee2-" + s + "@example.com", "ROLE_USER");
        disabled.setEnabled(false);
        userRepository.save(disabled);

        assertEquals(401, login(disabled.getEmail(), PASSWORD).getResponse().getStatus());
        for (UserEntity enabled : new UserEntity[]{customerA, cashier, admin}) {
            MvcResult ok = login(enabled.getEmail(), PASSWORD);
            assertEquals(200, ok.getResponse().getStatus());
            assertEquals(enabled.getRole(), body(ok).get("role").asText());
        }
    }

    // ---- /orders/my-orders is ROLE_USER only ----

    @Test
    void myOrders_isCustomerOnly() throws Exception {
        assertEquals(200, perform(as(get("/orders/my-orders"), customerA)).getResponse().getStatus());
        assertEquals(403, perform(as(get("/orders/my-orders"), admin)).getResponse().getStatus());
        assertEquals(403, perform(as(get("/orders/my-orders"), cashier)).getResponse().getStatus());
        assertEquals(401, perform(get("/orders/my-orders")).getResponse().getStatus());
    }

    @Test
    void myOrders_isStillTheCustomersOwnUnifiedHistory() throws Exception {
        String online = onlineOrder(customerA);
        String linkedPos = posSale(customerA);
        String walkIn = posSale(null);
        String othersOnline = onlineOrder(customerB);
        String othersPos = posSale(customerB);

        // any attempt to point the endpoint at someone else is ignored
        MvcResult result = perform(as(get("/orders/my-orders").param("userId", customerB.getUserId())
                .param("customerUserId", customerB.getUserId()).param("targetUserId", customerB.getUserId()), customerA));

        assertEquals(200, result.getResponse().getStatus());
        JsonNode history = body(result);
        assertEquals(2, history.size());
        String raw = result.getResponse().getContentAsString();
        assertTrue(raw.contains(online));
        assertTrue(raw.contains(linkedPos));
        assertFalse(raw.contains(walkIn));
        assertFalse(raw.contains(othersOnline));
        assertFalse(raw.contains(othersPos));
    }

    @Test
    void adminAllOrders_andCashierMySales_areUnaffected() throws Exception {
        String online = onlineOrder(customerA);
        String pos = posSale(null);

        String all = perform(as(get("/admin/orders").param("size", "100"), admin)).getResponse().getContentAsString();
        assertTrue(all.contains(online));
        assertTrue(all.contains(pos));

        MvcResult sales = perform(as(get("/pos/sales"), cashier));
        assertEquals(200, sales.getResponse().getStatus());
        assertTrue(sales.getResponse().getContentAsString().contains(pos));
        assertFalse(sales.getResponse().getContentAsString().contains(online));
    }
}
