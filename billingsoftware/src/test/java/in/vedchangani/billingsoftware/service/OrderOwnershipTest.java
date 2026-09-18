package in.vedchangani.billingsoftware.service;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Focused tests for order ownership:
 *  - order creation assigns the authenticated user, never a client-supplied id
 *  - a user only ever sees their own orders (query scoped in the DB layer)
 *  - a user cannot see another user's orders
 *  - an admin can see every order via the existing /orders/latest path
 */
@ExtendWith(MockitoExtension.class)
class OrderOwnershipTest {

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private RazorpayService razorpayService;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
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

    private OrderRequest anOrderRequest() {
        return OrderRequest.builder()
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 2)))
                .paymentMethod(PaymentMethod.CASH.name())
                .build();
    }

    @Test
    void createOrder_assignsAuthenticatedUserAsOwner() {
        UserEntity aliceEntity = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(aliceEntity));
        when(itemRepository.findByItemId("ITEM1")).thenReturn(Optional.of(anItem("ITEM1", "Burger", 50.0)));
        when(itemRepository.reserveStock("ITEM1", 2)).thenReturn(1);
        when(itemRepository.commitReservedStock("ITEM1", 2)).thenReturn(1);
        when(orderEntityRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        orderService.createOrder(anOrderRequest());

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository).save(captor.capture());
        assertEquals(aliceEntity, captor.getValue().getUser());
        assertEquals(1L, captor.getValue().getUser().getId());
    }

    @Test
    void getMyOrders_returnsOnlyOwnOrders() {
        UserEntity alice = aUser(1L, "alice@example.com");
        authenticateAs("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));

        OrderEntity aliceOrder = OrderEntity.builder()
                .orderId("ORD1").customerName("Alice").phoneNumber("111")
                .subtotal(10.0).tax(1.0).grandTotal(11.0)
                .paymentMethod(PaymentMethod.CASH).items(List.of()).user(alice)
                .build();
        when(orderEntityRepository.findByUser_IdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(aliceOrder));

        List<OrderResponse> result = orderService.getMyOrders();

        assertEquals(1, result.size());
        assertEquals("ORD1", result.get(0).getOrderId());
        // Must query the database scoped by the authenticated user's id,
        // never fetch everything and filter in Java.
        verify(orderEntityRepository).findByUser_IdOrderByCreatedAtDesc(1L);
        verify(orderEntityRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void getMyOrders_doesNotReturnAnotherUsersOrders() {
        UserEntity bob = aUser(2L, "bob@example.com");
        authenticateAs("bob@example.com");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));

        // Repository is queried scoped to bob's id (2L) and correctly returns nothing,
        // simulating that all existing orders in the DB belong to alice (id 1L).
        when(orderEntityRepository.findByUser_IdOrderByCreatedAtDesc(2L))
                .thenReturn(List.of());

        List<OrderResponse> result = orderService.getMyOrders();

        assertTrue(result.isEmpty());
        verify(orderEntityRepository).findByUser_IdOrderByCreatedAtDesc(2L);
        verify(orderEntityRepository, never()).findByUser_IdOrderByCreatedAtDesc(eq(1L));
    }

    @Test
    void getLatestOrders_returnsAllOrders_forAdmin() {
        UserEntity alice = aUser(1L, "alice@example.com");
        UserEntity bob = aUser(2L, "bob@example.com");
        OrderEntity aliceOrder = OrderEntity.builder()
                .orderId("ORD1").customerName("Alice").phoneNumber("111")
                .subtotal(10.0).tax(1.0).grandTotal(11.0)
                .paymentMethod(PaymentMethod.CASH).items(List.of()).user(alice)
                .build();
        OrderEntity bobOrder = OrderEntity.builder()
                .orderId("ORD2").customerName("Bob").phoneNumber("222")
                .subtotal(20.0).tax(2.0).grandTotal(22.0)
                .paymentMethod(PaymentMethod.CASH).items(List.of()).user(bob)
                .build();
        when(orderEntityRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(aliceOrder, bobOrder));

        List<OrderResponse> result = orderService.getLatestOrders();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(o -> o.getOrderId().equals("ORD1")));
        assertTrue(result.stream().anyMatch(o -> o.getOrderId().equals("ORD2")));
    }
}
