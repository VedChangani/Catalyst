package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PaymentVerificationRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request);

    void deleteOrder(String orderId);

    List<OrderResponse> getLatestOrders();

    /**
     * Returns only the orders belonging to the currently authenticated user, resolved from
     * Spring Security's authentication principal (never from a client-supplied id).
     */
    List<OrderResponse> getMyOrders();

    OrderResponse verifyPayment(PaymentVerificationRequest request);

    /**
     * Cancel a PENDING_PAYMENT order. Only the order's owner may cancel.
     */
    OrderResponse cancelOrder(String orderId);

    /**
     * Mark a PENDING_PAYMENT order as PAYMENT_FAILED. Only the order's owner may invoke.
     */
    OrderResponse failPayment(String orderId);

    Double sumSalesByDate(LocalDate date);

    Long countByOrderDate(LocalDate date);

    List<OrderResponse> findRecentOrders();
}
