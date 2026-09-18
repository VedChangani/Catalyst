package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentDetails;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the ADMIN-only deleteOrder path (DELETE /orders/{orderId}): an order that
 * still holds an uncommitted inventory reservation must not be deletable, since deleting it would
 * strand that reservedQuantity forever - nothing would ever be left to commit or release it.
 * Orders with no live reservation (PAID, CANCELLED, PAYMENT_FAILED, or a legacy order with a
 * NULL inventoryReserved flag) keep the existing unconditional delete behavior.
 */
@ExtendWith(MockitoExtension.class)
class OrderDeletionTest {

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private RazorpayService razorpayService;

    private OrderServiceImpl orderService;

    private OrderEntity anOrder(OrderStatus status, Boolean inventoryReserved) {
        return OrderEntity.builder()
                .orderId("ORD1")
                .customerName("Walk-in Customer")
                .phoneNumber("9999999999")
                .subtotal(100.0).tax(1.0).grandTotal(101.0)
                .paymentMethod(PaymentMethod.UPI)
                .orderStatus(status)
                .paymentDetails(PaymentDetails.builder().status(PaymentDetails.PaymentStatus.PENDING).build())
                .inventoryReserved(inventoryReserved)
                .items(List.of())
                .build();
    }

    @Test
    void deleteOrder_rejectsPendingOrderWithActiveReservation() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
        OrderEntity reservedOrder = anOrder(OrderStatus.PENDING_PAYMENT, true);
        when(orderEntityRepository.findByOrderId("ORD1")).thenReturn(Optional.of(reservedOrder));

        ConflictException ex = assertThrows(ConflictException.class, () -> orderService.deleteOrder("ORD1"));
        assertEquals(true, ex.getMessage().contains("ORD1"));
        verify(orderEntityRepository, never()).delete(org.mockito.ArgumentMatchers.any(in.vedchangani.billingsoftware.entity.OrderEntity.class));
    }

    @Test
    void deleteOrder_allowsDeletingAPaidOrder_whichHoldsNoReservation() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
        OrderEntity paidOrder = anOrder(OrderStatus.PAID, false);
        when(orderEntityRepository.findByOrderId("ORD1")).thenReturn(Optional.of(paidOrder));

        orderService.deleteOrder("ORD1");

        verify(orderEntityRepository).delete(paidOrder);
    }

    @Test
    void deleteOrder_allowsDeletingALegacyOrderWithNullInventoryReservedFlag() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
        // Legacy order created before inventory tracking existed - never reserved anything.
        OrderEntity legacyOrder = anOrder(OrderStatus.PENDING_PAYMENT, null);
        when(orderEntityRepository.findByOrderId("ORD1")).thenReturn(Optional.of(legacyOrder));

        orderService.deleteOrder("ORD1");

        verify(orderEntityRepository).delete(legacyOrder);
    }

    @Test
    void deleteOrder_allowsDeletingACancelledOrder_afterItsReservationWasReleased() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
        // cancelOrder clears inventoryReserved to false once the release succeeds.
        OrderEntity cancelledOrder = anOrder(OrderStatus.CANCELLED, false);
        when(orderEntityRepository.findByOrderId("ORD1")).thenReturn(Optional.of(cancelledOrder));

        orderService.deleteOrder("ORD1");

        verify(orderEntityRepository).delete(cancelledOrder);
    }

    @Test
    void deleteOrder_rejectsNonExistentOrder() {
        orderService = new OrderServiceImpl(orderEntityRepository, userRepository, itemRepository, razorpayService);
        when(orderEntityRepository.findByOrderId("GHOST")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.deleteOrder("GHOST"));
        verify(orderEntityRepository, never()).delete(org.mockito.ArgumentMatchers.any(in.vedchangani.billingsoftware.entity.OrderEntity.class));
    }
}
