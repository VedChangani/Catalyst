package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.CategoryEntity;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.repository.CategoryRepository;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many checkouts race for the last few units of one item, each on its own thread and its own real
 * transaction. Whatever the interleaving, stock must never be oversold: the atomic conditional
 * reserve UPDATE lets exactly the available quantity through, and the counters stay consistent.
 *
 * Scope, stated honestly: this runs on H2, which does not reproduce MySQL/InnoDB row-locking
 * behaviour, so it is a regression guard for the application's own no-oversell logic (a
 * read-then-write reservation would fail it), not a proof of InnoDB concurrency correctness.
 * Deliberately NOT @Transactional: every service call must commit or roll back for real.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentStockReservationTest {

    private static final int THREADS = 12;

    @Autowired private OrderService orderService;
    @Autowired private ItemRepository itemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderEntityRepository orderEntityRepository;

    private UserEntity customer;
    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString();
        customer = userRepository.save(UserEntity.builder()
                .userId("conc-" + s).email("concurrent-" + s + "@example.com").password("not-used")
                .role("ROLE_USER").name("Concurrent Customer").mobile(TestMobiles.next()).build());
        category = categoryRepository.save(CategoryEntity.builder()
                .categoryId("conc-cat-" + s).name("Concurrency " + s).build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        orderEntityRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    private ItemEntity itemWithStock(int stock) {
        return itemRepository.save(ItemEntity.builder()
                .itemId("conc-item-" + UUID.randomUUID()).name("Last Units").price(BigDecimal.TEN)
                .category(category).stockQuantity(stock).reservedQuantity(0)
                .lowStockThreshold(1).active(true).build());
    }

    private ItemEntity reload(ItemEntity item) {
        return itemRepository.findByItemId(item.getItemId()).orElseThrow();
    }

    private record Outcome(OrderResponse order, Throwable failure) {
    }

    // THREADS customers press "buy 1" at the same moment
    private List<Outcome> raceForOneUnitEach(ItemEntity item, String paymentMethod) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(customer.getEmail(), null, List.of()));
                try {
                    ready.countDown();
                    go.await();
                    return new Outcome(orderService.createOrder(OrderRequest.builder()
                            .paymentMethod(paymentMethod)
                            .cartItems(List.of(new OrderRequest.OrderItemRequest(item.getItemId(), 1)))
                            .build()), null);
                } catch (Throwable ex) {
                    return new Outcome(null, ex);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "threads did not start");
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private static long succeeded(List<Outcome> outcomes) {
        return outcomes.stream().filter(o -> o.order() != null).count();
    }

    // the only acceptable way to lose the race is the business conflict - not an error of any other kind
    private static void assertOnlyStockConflictsFailed(List<Outcome> outcomes) {
        outcomes.stream().filter(o -> o.failure() != null).forEach(o -> assertTrue(
                o.failure() instanceof ConflictException, "unexpected failure: " + o.failure()));
    }

    @Test
    void cashCheckoutsRacingForScarceStock_neverOversell_andEveryUnitIsSoldExactlyOnce() throws Exception {
        ItemEntity item = itemWithStock(5);

        List<Outcome> outcomes = raceForOneUnitEach(item, "CASH");

        assertOnlyStockConflictsFailed(outcomes);
        assertEquals(5, succeeded(outcomes), "exactly the available quantity may be sold");
        ItemEntity after = reload(item);
        assertEquals(0, after.getStockQuantity(), "all 5 units sold, none lost or invented");
        assertEquals(0, after.getReservedQuantity(), "a CASH sale leaves no reservation behind");
        assertEquals(5, orderEntityRepository.count(), "one persisted order per successful checkout, none for the losers");
    }

    @Test
    void upiReservationsRacingForScarceStock_neverExceedStock_andCancellingThemReleasesEverything() throws Exception {
        ItemEntity item = itemWithStock(4);

        List<Outcome> outcomes = raceForOneUnitEach(item, "UPI");

        assertOnlyStockConflictsFailed(outcomes);
        assertEquals(4, succeeded(outcomes), "no more units may be reserved than exist");
        ItemEntity held = reload(item);
        assertEquals(4, held.getStockQuantity(), "a reservation does not commit stock");
        assertEquals(4, held.getReservedQuantity());
        assertEquals(0, held.getStockQuantity() - held.getReservedQuantity(), "nothing left to promise anyone");

        // cancelling every pending order gives every unit back - once each
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(customer.getEmail(), null, List.of()));
        for (Outcome outcome : outcomes) {
            if (outcome.order() != null) {
                assertEquals(OrderStatus.PENDING_PAYMENT, outcome.order().getOrderStatus());
                assertEquals(OrderStatus.CANCELLED, orderService.cancelOrder(outcome.order().getOrderId()).getOrderStatus());
            }
        }
        ItemEntity released = reload(item);
        assertEquals(4, released.getStockQuantity());
        assertEquals(0, released.getReservedQuantity(), "the reservations were released exactly once");
    }
}
