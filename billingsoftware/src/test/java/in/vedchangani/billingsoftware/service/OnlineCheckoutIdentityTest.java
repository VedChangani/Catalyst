package in.vedchangani.billingsoftware.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.SalesChannel;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A2: an ONLINE order's customer identity comes only from the authenticated account. Runs through
 * the real security chain, controller, service and H2 database; deliberately NOT @Transactional so
 * the order really commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnlineCheckoutIdentityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;

    private UserEntity aaron;
    private UserEntity bella;
    private ItemEntity item;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        aaron = aUser("Aaron Customer", "aaron-" + s + "@example.com", TestMobiles.next());
        bella = aUser("Bella Customer", "bella-" + s + "@example.com", TestMobiles.next());
        CategoryEntity category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("oci-cat-" + s).name("Identity " + s).build());
        item = itemRepository.save(ItemEntity.builder()
                .itemId("oci-item-" + s).name("Widget").price(BigDecimal.valueOf(10))
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

    private UserEntity aUser(String name, String email, String mobile) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).password("not-used")
                .role("ROLE_USER").name(name).mobile(mobile).build());
    }

    private String body(String extraFields) {
        return "{" + extraFields + "\"paymentMethod\":\"CASH\",\"cartItems\":[{\"itemId\":\""
                + item.getItemId() + "\",\"quantity\":1}]}";
    }

    private MvcResult placeOrder(UserEntity actor, String body) throws Exception {
        return mockMvc.perform(post("/orders").with(user(actor.getEmail()).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private OrderEntity stored(MvcResult created) throws Exception {
        return orderEntityRepository.findByOrderId(json(created).get("orderId").asText()).orElseThrow();
    }

    @Test
    void orderWithoutIdentityFields_snapshotsTheAuthenticatedAccount() throws Exception {
        MvcResult result = placeOrder(aaron, body(""));

        assertEquals(201, result.getResponse().getStatus());
        OrderEntity order = stored(result);
        assertEquals("Aaron Customer", order.getCustomerName());
        assertEquals(aaron.getMobile(), order.getPhoneNumber());
        assertEquals(aaron.getId(), order.getUser().getId());
        assertEquals(SalesChannel.ONLINE, order.getSalesChannel());
        assertNull(order.getCreatedBy());
        // the response still exposes the historical snapshot
        assertEquals("Aaron Customer", json(result).get("customerName").asText());
        assertEquals(aaron.getMobile(), json(result).get("phoneNumber").asText());
    }

    @Test
    void maliciousIdentityFields_areIgnored_accountIdentityWins() throws Exception {
        String malicious = "\"customerName\":\"" + bella.getName() + "\",\"phoneNumber\":\"" + bella.getMobile()
                + "\",\"userId\":\"" + bella.getUserId() + "\",\"user\":{\"id\":" + bella.getId()
                + "},\"createdBy\":\"" + bella.getUserId() + "\",\"salesChannel\":\"POS\",";

        MvcResult result = placeOrder(aaron, body(malicious));

        assertEquals(201, result.getResponse().getStatus());
        OrderEntity order = stored(result);
        assertEquals(aaron.getId(), order.getUser().getId());
        assertEquals("Aaron Customer", order.getCustomerName());
        assertEquals(aaron.getMobile(), order.getPhoneNumber());
        assertEquals(SalesChannel.ONLINE, order.getSalesChannel());
        assertNull(order.getCreatedBy());
        assertEquals(0, orderEntityRepository.findAll().stream()
                .filter(o -> o.getUser() != null && o.getUser().getId().equals(bella.getId())).count());
    }

    @Test
    void laterProfileChange_doesNotAlterExistingOrderSnapshot() throws Exception {
        String originalMobile = aaron.getMobile();
        OrderEntity order = stored(placeOrder(aaron, body("")));

        UserEntity account = userRepository.findById(aaron.getId()).orElseThrow();
        account.setName("Aaron Renamed");
        account.setMobile(TestMobiles.next());
        userRepository.save(account);

        OrderEntity reloaded = orderEntityRepository.findByOrderId(order.getOrderId()).orElseThrow();
        assertEquals("Aaron Customer", reloaded.getCustomerName());
        assertEquals(originalMobile, reloaded.getPhoneNumber());

        // a new order after the change snapshots the NEW details; the old one is untouched
        OrderEntity next = stored(placeOrder(account, body("")));
        assertEquals("Aaron Renamed", next.getCustomerName());
        assertEquals(account.getMobile(), next.getPhoneNumber());
        assertEquals("Aaron Customer", orderEntityRepository.findByOrderId(order.getOrderId()).orElseThrow().getCustomerName());
    }

    @Test
    void accountWithoutMobile_cannotCheckOut_andRequestValuesAreNotAFallback() throws Exception {
        UserEntity legacy = userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email("legacy-" + UUID.randomUUID() + "@example.com")
                .password("not-used").role("ROLE_USER").name("Legacy Customer").build());

        MvcResult result = placeOrder(legacy,
                body("\"customerName\":\"Typed Name\",\"phoneNumber\":\"9999999999\","));

        assertEquals(400, result.getResponse().getStatus());
        assertTrue(json(result).get("message").asText().contains("Complete your profile"));
        assertEquals(0, orderEntityRepository.count());
        // nothing was reserved or committed
        ItemEntity after = itemRepository.findByItemId(item.getItemId()).orElseThrow();
        assertEquals(100, after.getStockQuantity());
        assertEquals(0, after.getReservedQuantity());
    }

    @Test
    void accountWithBlankName_cannotCheckOut() throws Exception {
        UserEntity nameless = userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email("nameless-" + UUID.randomUUID() + "@example.com")
                .password("not-used").role("ROLE_USER").name("  ").mobile(TestMobiles.next()).build());

        assertEquals(400, placeOrder(nameless, body("")).getResponse().getStatus());
        assertEquals(0, orderEntityRepository.count());
    }
}
