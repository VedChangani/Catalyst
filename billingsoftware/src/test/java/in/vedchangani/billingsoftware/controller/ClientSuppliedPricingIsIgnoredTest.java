package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.TestMoney;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientSuppliedPricingIsIgnoredTest {

    private static final String CATALOG_PRICE = "19.99";
    private static final int QUANTITY = 3;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;

    private UserEntity customer;
    private UserEntity cashier;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        customer = account("Price Customer", "price-customer-" + s + "@example.com", "ROLE_USER");
        cashier = account("Price Cashier", "price-cashier-" + s + "@example.com", "ROLE_CASHIER");
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("price-cat-" + s).name("Pricing " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("price-item-" + s).name("Real Widget").price(new BigDecimal(CATALOG_PRICE))
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
                .mobile(TestMobiles.next()).password("not-used").build());
    }

    private String hostileBody(String extraTopLevel) {
        return "{" + extraTopLevel
                + "\"paymentMethod\":\"CASH\","
                + "\"subtotal\":0.01,\"tax\":0,\"grandTotal\":0.01,\"total\":0.01,\"amount\":1,"
                + "\"cartItems\":[{\"itemId\":\"" + item.getItemId() + "\",\"quantity\":" + QUANTITY
                + ",\"price\":0.01,\"name\":\"Free Widget\",\"lineTotal\":0.03}]}";
    }

    private JsonNode created(String url, UserEntity actor, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                .with(user(actor.getEmail()).roles(actor.getRole().replace("ROLE_", "")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertServerComputedTotals(JsonNode response) {
        String orderId = response.get("orderId").asText();

        TestMoney.assertMoney("59.97", response.get("subtotal").decimalValue());
        TestMoney.assertMoney("0.60", response.get("tax").decimalValue());
        TestMoney.assertMoney("60.57", response.get("grandTotal").decimalValue());
        JsonNode line = response.get("items").get(0);
        assertEquals("Real Widget", line.get("name").asText());
        TestMoney.assertMoney(CATALOG_PRICE, line.get("price").decimalValue());
        TestMoney.assertMoney("59.97", line.get("lineTotal").decimalValue());

        OrderEntity stored = orderEntityRepository.findByOrderId(orderId).orElseThrow();
        TestMoney.assertMoney("59.97", stored.getSubtotal());
        TestMoney.assertMoney("0.60", stored.getTax());
        TestMoney.assertMoney("60.57", stored.getGrandTotal());
    }

    @Test
    void onlineOrder_persistsCatalogPricesAndServerComputedTotals_whateverTheClientSends() throws Exception {
        JsonNode response = created("/orders", customer, hostileBody(""));

        assertServerComputedTotals(response);

        MvcResult read = mockMvc.perform(get("/orders/" + response.get("orderId").asText())
                .with(user(customer.getEmail()).roles("USER"))).andReturn();
        assertEquals(200, read.getResponse().getStatus());
        JsonNode detail = objectMapper.readTree(read.getResponse().getContentAsString());
        assertEquals("Real Widget", detail.get("items").get(0).get("name").asText());
        TestMoney.assertMoney("60.57", detail.get("grandTotal").decimalValue());
    }

    @Test
    void posOrder_persistsCatalogPricesAndServerComputedTotals_whateverTheClientSends() throws Exception {
        JsonNode response = created("/pos/orders", cashier,
                hostileBody("\"customerUserId\":\"" + customer.getUserId() + "\","));

        assertServerComputedTotals(response);
        assertEquals(customer.getUserId(), response.get("customer").get("userId").asText());
    }
}
