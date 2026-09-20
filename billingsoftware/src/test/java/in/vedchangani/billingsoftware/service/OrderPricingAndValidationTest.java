package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.TestMoney;
import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Focused tests for createOrder's server-side pricing and validation:
 *  - subtotal/tax/grandTotal are computed from the catalog price, never from the client
 *  - a client-supplied price/tax/total is ignored entirely
 *  - quantities are multiplied per line and summed across multiple lines
 *  - an empty cart is rejected
 *  - a zero or negative quantity is rejected
 *  - a non-existent item id is rejected
 */
@ExtendWith(MockitoExtension.class)
class OrderPricingAndValidationTest {

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private RazorpayService razorpayService;

    @Mock
    private AuditService auditService;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService, auditService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private UserEntity aUser(Long id, String email) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail(email);
        user.setRole("ROLE_USER");
        user.setName("Customer " + id);
        user.setMobile("987654321" + id);
        return user;
    }

    private ItemEntity anItem(String itemId, String name, double price) {
        return ItemEntity.builder()
                .id(1L)
                .itemId(itemId)
                .name(name)
                .price(BigDecimal.valueOf(price))
                .active(true)
                .stockQuantity(100)
                .reservedQuantity(0)
                .build();
    }

    // These tests are about pricing, not inventory: every reservation/commit succeeds.
    private void stockAlwaysAvailable() {
        when(itemRepository.reserveStock(anyString(), anyInt())).thenReturn(1);
        when(itemRepository.commitReservedStock(anyString(), anyInt())).thenReturn(1);
    }

    // ---- Server computes subtotal/tax/grandTotal from the catalog price, ignoring the client ----
    @Test
    void createOrder_computesTotalsFromCatalogPrice_ignoringClientValues() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", "Burger", 100.0)));
        stockAlwaysAvailable();
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderRequest request = OrderRequest.builder()
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 2)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        OrderResponse result = orderService.createOrder(request);

        // subtotal = 100 * 2 = 200, tax = 1% of 200 = 2, grandTotal = 202
        TestMoney.assertMoney("200.0", result.getSubtotal());
        TestMoney.assertMoney("2.0", result.getTax());
        TestMoney.assertMoney("202.0", result.getGrandTotal());
        assertEquals("Burger", result.getItems().get(0).getName());
        TestMoney.assertMoney("100.0", result.getItems().get(0).getPrice());

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository).save(captor.capture());
        TestMoney.assertMoney("200.0", captor.getValue().getSubtotal());
        TestMoney.assertMoney("2.0", captor.getValue().getTax());
        TestMoney.assertMoney("202.0", captor.getValue().getGrandTotal());
    }

    // ---- Multiple lines are summed correctly ----
    @Test
    void createOrder_sumsMultipleLineItems() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", "Burger", 100.0)));
        when(itemRepository.findByItemId("ITEM2")).thenReturn(Optional.of(anItem("ITEM2", "Fries", 50.0)));
        stockAlwaysAvailable();
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderRequest request = OrderRequest.builder()
                .cartItems(List.of(
                        new OrderRequest.OrderItemRequest("ITEM1", 1),
                        new OrderRequest.OrderItemRequest("ITEM2", 3)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        OrderResponse result = orderService.createOrder(request);

        // subtotal = (100*1) + (50*3) = 250, tax = 2.5, grandTotal = 252.5
        TestMoney.assertMoney("250.0", result.getSubtotal());
        TestMoney.assertMoney("2.5", result.getTax());
        TestMoney.assertMoney("252.5", result.getGrandTotal());
    }

    // ---- Client-supplied price/tax/total on the request are ignored (request has no such fields) ----
    @Test
    void createOrder_ignoresAnyClientSuppliedPricing() {
        // OrderRequest.OrderItemRequest only exposes itemId + quantity, so there is no
        // price/tax/total field a client could tamper with in the first place. This test
        // documents that guarantee by asserting totals always come from the catalog price
        // even when a very "suspicious" quantity/item combination is used.
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", "Burger", 9.99)));
        stockAlwaysAvailable();
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderRequest request = OrderRequest.builder()
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 1)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        OrderResponse result = orderService.createOrder(request);

        TestMoney.assertMoney("9.99", result.getSubtotal());
        // 1% of 9.99 is 0.0999; money is kept in whole paise, so tax is rounded HALF_UP to 0.10
        // (previously a double 0.0999 was stored while 0.10 was displayed and charged).
        TestMoney.assertMoney("0.10", result.getTax());
        TestMoney.assertMoney("10.09", result.getGrandTotal());
    }

    // ---- Empty cart is rejected ----
    @Test
    void createOrder_rejectsEmptyCart() {
        authenticateAs("alice@example.com");

        OrderRequest request = OrderRequest.builder()
                .cartItems(List.of())
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(request));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Null cart is rejected ----
    @Test
    void createOrder_rejectsNullCart() {
        authenticateAs("alice@example.com");

        OrderRequest request = OrderRequest.builder()
                .cartItems(null)
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(request));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Zero quantity is rejected ----
    @Test
    void createOrder_rejectsZeroQuantity() {
        authenticateAs("alice@example.com");

        OrderRequest request = OrderRequest.builder()
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 0)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(request));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Negative quantity is rejected ----
    @Test
    void createOrder_rejectsNegativeQuantity() {
        authenticateAs("alice@example.com");

        OrderRequest request = OrderRequest.builder()
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", -1)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(request));
        verify(orderEntityRepository, never()).save(any());
    }

    // ---- Non-existent item id is rejected ----
    @Test
    void createOrder_rejectsNonExistentItem() {
        authenticateAs("alice@example.com");
        when(itemRepository.findByItemId("GHOST")).thenReturn(Optional.empty());

        OrderRequest request = OrderRequest.builder()
                .cartItems(List.of(new OrderRequest.OrderItemRequest("GHOST", 1)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();

        assertThrows(RuntimeException.class, () -> orderService.createOrder(request));
        verify(orderEntityRepository, never()).save(any());
    }
}
