package in.vedchangani.billingsoftware.repository;

import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused tests for the inventory-mutation queries added to ItemRepository in the inventory
 * foundation batch: reserveStock, commitReservedStock, releaseReservedStock, adjustStockQuantity.
 *
 * These run against the H2 ("test" profile, MODE=MySQL) datasource used by every other Spring
 * context test in this project - NOT a real MySQL/InnoDB instance. They verify the *query logic*
 * (the WHERE-clause guards produce the right affected-row count in each case), not true InnoDB
 * row-locking/concurrency behavior. Genuine concurrent-transaction correctness under MySQL InnoDB
 * is a documented follow-up (see the approved inventory plan, section J) and is intentionally not
 * claimed by this test class.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ItemRepositoryTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("CAT1")
                .name("Beverages")
                .build());
    }

    private ItemEntity anItem(String itemId, int stock, int reserved, boolean active) {
        return itemRepository.save(ItemEntity.builder()
                .itemId(itemId)
                .name("Item " + itemId)
                .price(BigDecimal.valueOf(10))
                .category(category)
                .stockQuantity(stock)
                .reservedQuantity(reserved)
                .active(active)
                .build());
    }

    // ---- reserveStock ----

    @Test
    void reserveStock_succeedsWhenSufficientAvailableStockExists() {
        anItem("ITEM1", 10, 0, true);

        int updated = itemRepository.reserveStock("ITEM1", 4);

        assertEquals(1, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(4, reloaded.getReservedQuantity());
        assertEquals(10, reloaded.getStockQuantity());
    }

    @Test
    void reserveStock_failsWhenAvailableStockIsInsufficient() {
        // stock=5, already reserved=3 -> available=2, requesting 3 must fail
        anItem("ITEM1", 5, 3, true);

        int updated = itemRepository.reserveStock("ITEM1", 3);

        assertEquals(0, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(3, reloaded.getReservedQuantity(), "a failed reservation must not mutate reservedQuantity");
    }

    @Test
    void reserveStock_failsForInactiveItem() {
        anItem("ITEM1", 10, 0, false);

        int updated = itemRepository.reserveStock("ITEM1", 1);

        assertEquals(0, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(0, reloaded.getReservedQuantity());
    }

    // ---- commitReservedStock ----

    @Test
    void commitReservedStock_succeedsWhenEnoughIsReserved() {
        anItem("ITEM1", 10, 4, true);

        int updated = itemRepository.commitReservedStock("ITEM1", 4);

        assertEquals(1, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(6, reloaded.getStockQuantity());
        assertEquals(0, reloaded.getReservedQuantity());
    }

    @Test
    void commitReservedStock_refusesToDropReservedQuantityBelowZero() {
        // only 2 reserved, trying to commit 5 must be refused entirely
        anItem("ITEM1", 10, 2, true);

        int updated = itemRepository.commitReservedStock("ITEM1", 5);

        assertEquals(0, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(2, reloaded.getReservedQuantity(), "a failed commit must not partially mutate reservedQuantity");
        assertEquals(10, reloaded.getStockQuantity(), "a failed commit must not touch stockQuantity");
    }

    // ---- releaseReservedStock ----

    @Test
    void releaseReservedStock_succeedsWhenEnoughIsReserved() {
        anItem("ITEM1", 10, 4, true);

        int updated = itemRepository.releaseReservedStock("ITEM1", 4);

        assertEquals(1, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(0, reloaded.getReservedQuantity());
        assertEquals(10, reloaded.getStockQuantity(), "release must never touch stockQuantity");
    }

    @Test
    void releaseReservedStock_refusesToDropReservedQuantityBelowZero() {
        anItem("ITEM1", 10, 2, true);

        int updated = itemRepository.releaseReservedStock("ITEM1", 5);

        assertEquals(0, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(2, reloaded.getReservedQuantity(), "a failed release must not partially mutate reservedQuantity");
    }

    // ---- adjustStockQuantity ----

    @Test
    void adjustStockQuantity_allowsRestockingUpward() {
        anItem("ITEM1", 10, 4, true);

        int updated = itemRepository.adjustStockQuantity("ITEM1", 5);

        assertEquals(1, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(15, reloaded.getStockQuantity());
    }

    @Test
    void adjustStockQuantity_refusesToDropStockBelowReservedQuantity() {
        // stock=10, reserved=8 -> a delta of -5 would leave stock=5 < reserved=8, must be refused
        anItem("ITEM1", 10, 8, true);

        int updated = itemRepository.adjustStockQuantity("ITEM1", -5);

        assertEquals(0, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(10, reloaded.getStockQuantity(), "a refused adjustment must not partially mutate stockQuantity");
    }

    @Test
    void everySuccessfulStockMutation_incrementsTheVersion() {
        long initialVersion = anItem("ITEM1", 10, 0, true).getVersion();

        itemRepository.reserveStock("ITEM1", 2);
        itemRepository.commitReservedStock("ITEM1", 1);
        itemRepository.releaseReservedStock("ITEM1", 1);
        itemRepository.adjustStockQuantity("ITEM1", 5);

        assertEquals(initialVersion + 4, itemRepository.findByItemId("ITEM1").orElseThrow().getVersion());
    }

    @Test
    void aRefusedStockMutation_doesNotIncrementTheVersion() {
        long initialVersion = anItem("ITEM1", 1, 0, true).getVersion();

        assertEquals(0, itemRepository.reserveStock("ITEM1", 5));

        assertEquals(initialVersion, itemRepository.findByItemId("ITEM1").orElseThrow().getVersion());
    }

    @Test
    void adjustStockQuantity_allowsNegativeDeltaThatStaysAtOrAboveReservedQuantity() {
        // stock=10, reserved=3 -> a delta of -5 leaves stock=5 >= reserved=3, must be allowed
        anItem("ITEM1", 10, 3, true);

        int updated = itemRepository.adjustStockQuantity("ITEM1", -5);

        assertEquals(1, updated);
        ItemEntity reloaded = itemRepository.findByItemId("ITEM1").orElseThrow();
        assertEquals(5, reloaded.getStockQuantity());
    }
}
