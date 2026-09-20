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

    /** Creates an ONLINE order for the authenticated customer. */
    OrderResponse createOrder(OrderRequest request);

    /**
     * Creates a POS order entered by the authenticated cashier (ROLE_CASHIER only), for a walk-in customer or
     * an explicitly selected registered customer.
     */
    OrderResponse createPosOrder(PosOrderRequest request);

    /**
     * Idempotent ONLINE create. With a null key this behaves exactly like createOrder(request).
     * With a key, a repeat of the same request by the same customer returns the existing order
     * (replayed = true) without touching inventory; the same key with a different request or
     * actor is a ConflictException.
     */
    OrderCreationResult createOrder(OrderRequest request, String idempotencyKey);

    /** Idempotent POS create; same semantics as {@link #createOrder(OrderRequest, String)}. */
    OrderCreationResult createPosOrder(PosOrderRequest request, String idempotencyKey);


    List<OrderResponse> getLatestOrders();

    /**
     * ADMIN order management: filtered, sorted, paginated orders of every channel. Filtering and
     * paging run in the database. Invalid paging/sort/range values raise IllegalArgumentException.
     */
    PagedResponse<AdminOrderSummaryResponse> getAdminOrders(AdminOrderQuery query);

    /**
     * ROLE_USER: only the orders whose customer (`user`) is the authenticated user, resolved from
     * Spring Security's authentication principal (never from a client-supplied id).
     */
    List<OrderResponse> getMyOrders();

    /** The authenticated cashier's own sales: createdBy = caller AND salesChannel = POS, newest first. */
    List<OrderResponse> getMySales();

    /**
     * One order: for a customer, from their own purchase history; for a cashier, a POS sale they
     * entered (createdBy + POS). 404 if it does not
     * exist; denied unless its `user` is the authenticated customer (walk-in POS orders, whose
     * user is null, are never accessible this way).
     */
    OrderResponse getMyOrder(String orderId);

    OrderResponse verifyPayment(PaymentVerificationRequest request);

    /**
     * Cancel a PENDING_PAYMENT order. Only the order's owner may cancel.
     */
    OrderResponse cancelOrder(String orderId);

    /**
     * Mark a PENDING_PAYMENT order as PAYMENT_FAILED. Only the order's owner may invoke.
     */
    OrderResponse failPayment(String orderId);

    BigDecimal sumSalesByDate(LocalDate date);

    Long countByOrderDate(LocalDate date);

    List<OrderResponse> findRecentOrders();
}
