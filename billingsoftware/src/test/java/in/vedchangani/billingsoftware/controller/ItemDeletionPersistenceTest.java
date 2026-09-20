package in.vedchangani.billingsoftware.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.PaymentMethod;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * DELETE /admin/items/{itemId} must really remove the row: 204 AND gone from the database AND
 * gone from GET /items. Real security chain, controller, service, Hibernate and H2; deliberately
 * NOT @Transactional so the service's own transaction commits for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ItemDeletionPersistenceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String s;
    private UserEntity admin;
    private UserEntity customer;
    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        admin = account("ROLE_ADMIN");
        customer = account("ROLE_USER");
        category = categoryRepository.save(CategoryEntity.builder().categoryId("del-cat-" + s).name("Del " + s)
                .imgUrl("http://localhost:8080/api/v1.0/uploads/none-" + s + ".png").build());
    }

    @AfterEach
    void tearDown() {
        orderEntityRepository.deleteAll();
        // itemRepository.deleteAll() would silently skip rows whose version is NULL (the very bug under
        // test), so the items are cleared with SQL.
        jdbcTemplate.update("DELETE FROM tbl_items");
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UserEntity account(String role) {
        return userRepository.save(UserEntity.builder().userId("uid-" + UUID.randomUUID())
                .email(role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .name(role).role(role).mobile(TestMobiles.next()).password("not-used").build());
    }

    // A row shaped like the ones in the real MySQL database: created before inventory tracking, so
    // version, active and reserved_quantity are NULL. (Rows saved through JPA get a version, which
    // is why a plain save-then-delete test can not reproduce the bug.)
    private ItemEntity legacyItem(String name) {
        ItemEntity saved = item(name, 0);
        jdbcTemplate.update("UPDATE tbl_items SET version = NULL, active = NULL, reserved_quantity = NULL WHERE id = ?", saved.getId());
        return itemRepository.findByItemId(saved.getItemId()).orElseThrow();
    }

    private ItemEntity item(String name, int reserved) {
        return itemRepository.save(ItemEntity.builder().itemId(UUID.randomUUID().toString()).name(name)
                .price(new BigDecimal("10.00")).category(category).stockQuantity(50).reservedQuantity(reserved)
                .lowStockThreshold(5).active(true)
                .imgUrl("http://localhost:8080/api/v1.0/uploads/none-" + UUID.randomUUID() + ".png").build());
    }

    private MvcResult deleteAs(UserEntity actor, String itemId) throws Exception {
        return mockMvc.perform(delete("/admin/items/" + itemId)
                .with(user(actor.getEmail()).roles(actor.getRole().replace("ROLE_", "")))).andReturn();
    }

    private boolean listedInItems(String itemId) throws Exception {
        MvcResult result = mockMvc.perform(get("/items").with(user(customer.getEmail()).roles("USER"))).andReturn();
        for (JsonNode row : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (itemId.equals(row.get("itemId").asText())) return true;
        }
        return false;
    }

    @Test
    void delete_returns204_andTheRowIsReallyGone_fromTheDatabaseAndFromGetItems() throws Exception {
        ItemEntity item = item("Samsung S26 Ultra", 0);
        assertTrue(itemRepository.findByItemId(item.getItemId()).isPresent());
        assertTrue(listedInItems(item.getItemId()));

        MvcResult result = deleteAs(admin, item.getItemId());

        assertEquals(204, result.getResponse().getStatus());
        assertTrue(itemRepository.findByItemId(item.getItemId()).isEmpty(), "row must be gone (by item_id)");
        assertTrue(itemRepository.findById(item.getId()).isEmpty(), "row must be gone (by primary key)");
        assertFalse(listedInItems(item.getItemId()), "GET /items must no longer return it");
        // deleting it again is a 404, not a second "success"
        assertEquals(404, deleteAs(admin, item.getItemId()).getResponse().getStatus());
    }

    @Test
    void legacyRow_withNullVersion_isReallyDeleted_notSilentlySkipped() throws Exception {
        ItemEntity legacy = legacyItem("Samsung S26 Ultra");
        assertNull(legacy.getVersion());
        assertNull(legacy.getReservedQuantity());
        assertTrue(listedInItems(legacy.getItemId()));

        MvcResult result = deleteAs(admin, legacy.getItemId());

        assertEquals(204, result.getResponse().getStatus());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tbl_items WHERE item_id = ?", Integer.class, legacy.getItemId()),
                "the row must be gone from the database");
        assertTrue(itemRepository.findByItemId(legacy.getItemId()).isEmpty());
        assertFalse(listedInItems(legacy.getItemId()));
    }

    @Test
    void delete_removesOnlyThatItem() throws Exception {
        ItemEntity gone = item("Delete me", 0);
        ItemEntity kept = item("Keep me", 0);

        assertEquals(204, deleteAs(admin, gone.getItemId()).getResponse().getStatus());

        assertTrue(itemRepository.findByItemId(kept.getItemId()).isPresent());
        assertTrue(listedInItems(kept.getItemId()));
    }

    @Test
    void historicalOrderLines_surviveDeletionOfTheirItem() throws Exception {
        ItemEntity item = item("Sold once", 0);
        List<OrderItemEntity> lines = new ArrayList<>();
        lines.add(OrderItemEntity.builder().itemId(item.getItemId()).name("Sold once")
                .price(new BigDecimal("10.00")).quantity(2).build());
        OrderEntity order = orderEntityRepository.save(OrderEntity.builder().customerName("C").phoneNumber("9000000000")
                .subtotal(new BigDecimal("20.00")).tax(new BigDecimal("0.20")).grandTotal(new BigDecimal("20.20"))
                .paymentMethod(PaymentMethod.CASH).orderStatus(OrderStatus.PAID)
                .paymentDetails(PaymentDetails.builder().status(PaymentDetails.PaymentStatus.COMPLETED).build())
                .items(lines).user(customer).salesChannel(SalesChannel.ONLINE).inventoryReserved(false).build());

        assertEquals(204, deleteAs(admin, item.getItemId()).getResponse().getStatus());

        assertTrue(itemRepository.findByItemId(item.getItemId()).isEmpty());
        assertTrue(orderEntityRepository.findByOrderId(order.getOrderId()).isPresent());
        java.util.Map<String, Object> line = jdbcTemplate.queryForMap(
                "SELECT item_id, name, price, quantity FROM tbl_order_items WHERE item_id = ?", item.getItemId());
        assertEquals("Sold once", line.get("name"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) line.get("price")));
        assertEquals(2, ((Number) line.get("quantity")).intValue());
    }

    @Test
    void reservedItems_cannotBeDeleted_andStayInTheDatabase() throws Exception {
        ItemEntity reserved = item("In a pending order", 3);

        MvcResult result = deleteAs(admin, reserved.getItemId());

        assertEquals(409, result.getResponse().getStatus());
        assertTrue(itemRepository.findByItemId(reserved.getItemId()).isPresent());
        assertTrue(listedInItems(reserved.getItemId()));
    }

    @Test
    void deletion_stillRequiresAdmin() throws Exception {
        ItemEntity item = item("Protected", 0);

        assertEquals(403, deleteAs(customer, item.getItemId()).getResponse().getStatus());
        assertEquals(401, mockMvc.perform(delete("/admin/items/" + item.getItemId())).andReturn().getResponse().getStatus());

        assertTrue(itemRepository.findByItemId(item.getItemId()).isPresent());
    }
}
