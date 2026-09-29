package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.AdminOrderQuery;
import in.vedchangani.billingsoftware.io.AdminOrderSummaryResponse;
import in.vedchangani.billingsoftware.io.PagedResponse;
import in.vedchangani.billingsoftware.io.OrderCreationResult;
import in.vedchangani.billingsoftware.io.OrderRequest;
import in.vedchangani.billingsoftware.io.OrderResponse;
import in.vedchangani.billingsoftware.io.PosOrderRequest;
import in.vedchangani.billingsoftware.io.PaymentVerificationRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request);

    OrderResponse createPosOrder(PosOrderRequest request);

    OrderCreationResult createOrder(OrderRequest request, String idempotencyKey);

    OrderCreationResult createPosOrder(PosOrderRequest request, String idempotencyKey);


    List<OrderResponse> getLatestOrders();

    PagedResponse<AdminOrderSummaryResponse> getAdminOrders(AdminOrderQuery query);

    List<OrderResponse> getMyOrders();

    List<OrderResponse> getMySales();

    OrderResponse getMyOrder(String orderId);

    OrderResponse verifyPayment(PaymentVerificationRequest request);

    OrderResponse cancelOrder(String orderId);

    OrderResponse failPayment(String orderId);

    BigDecimal sumSalesByDate(LocalDate date);

    Long countByOrderDate(LocalDate date);

    List<OrderResponse> findRecentOrders();
}
