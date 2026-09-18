package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.RazorpayService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    // Tax rate applied to the server-computed subtotal. Kept in sync with the
    // display-only calculation in the frontend cart summary (1%).
    private static final double TAX_RATE = 0.01;

    private final OrderEntityRepository orderEntityRepository;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    // Used only for cryptographic signature verification; the Razorpay key secret stays
    // inside RazorpayServiceImpl and never crosses this boundary.
    private final RazorpayService razorpayService;

    @Override
    public OrderResponse createOrder(OrderRequest request) {
        // Cart must contain at least one line item.
        if (request.getCartItems() == null || request.getCartItems().isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        // Item identity/name/price and the order's monetary totals are never trusted from the
        // client: each cart line is resolved to its authoritative ItemEntity by itemId, and
        // subtotal/tax/grandTotal are computed here from those resolved prices.
        List<OrderItemEntity> orderItems = request.getCartItems().stream()
                .map(this::convertToOrderItemEntity)
                .collect(Collectors.toList());

        double subtotal = orderItems.stream()
                .mapToDouble(item -> item.getPrice() * item.getQuantity())
                .sum();
        double tax = subtotal * TAX_RATE;
        double grandTotal = subtotal + tax;

        OrderEntity newOrder = OrderEntity.builder()
                .customerName(request.getCustomerName())
                .phoneNumber(request.getPhoneNumber())
                .subtotal(subtotal)
                .tax(tax)
                .grandTotal(grandTotal)
                .paymentMethod(PaymentMethod.valueOf(request.getPaymentMethod()))
                .build();

        // Ownership is derived exclusively from the authenticated principal (Spring Security),
        // never from client-supplied data such as a userId field, customerName, or phoneNumber.
        newOrder.setUser(getAuthenticatedUser());

        PaymentDetails paymentDetails = new PaymentDetails();
        if (newOrder.getPaymentMethod() == PaymentMethod.CASH) {
            paymentDetails.setStatus(PaymentDetails.PaymentStatus.COMPLETED);
            newOrder.setOrderStatus(OrderStatus.PAID);
        } else {
            paymentDetails.setStatus(PaymentDetails.PaymentStatus.PENDING);
            newOrder.setOrderStatus(OrderStatus.PENDING_PAYMENT);
        }
        newOrder.setPaymentDetails(paymentDetails);

        newOrder.setItems(orderItems);

        newOrder = orderEntityRepository.save(newOrder);
        return convertToResponse(newOrder);
    }

    /**
     * Resolves the UserEntity for the currently authenticated principal.
     * The principal's username (set by JwtRequestFilter/AppUserDetailsService) is the
     * user's email, so we look the user up by email - never by any client-supplied id.
     */
    private UserEntity getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("No authenticated user found");
        }
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found: " + email));
    }

    private OrderItemEntity convertToOrderItemEntity(OrderRequest.OrderItemRequest orderItemRequest) {
        // Quantity must be a positive number.
        if (orderItemRequest.getQuantity() == null || orderItemRequest.getQuantity() <= 0) {
            throw new IllegalArgumentException(
                    "Quantity must be greater than 0 for item: " + orderItemRequest.getItemId());
        }

        // Name and price are looked up server-side from the item catalog - never taken from
        // the request - so a client cannot alter what an item is called or how much it costs.
        ItemEntity item = itemRepository.findByItemId(orderItemRequest.getItemId())
                .orElseThrow(() -> new RuntimeException("Item not found: " + orderItemRequest.getItemId()));

        return OrderItemEntity.builder()
                .itemId(item.getItemId())
                .name(item.getName())
                .price(item.getPrice().doubleValue())
                .quantity(orderItemRequest.getQuantity())
                .build();
    }

    private OrderResponse convertToResponse(OrderEntity newOrder) {
        return OrderResponse.builder()
                .orderId(newOrder.getOrderId())
                .customerName(newOrder.getCustomerName())
                .phoneNumber(newOrder.getPhoneNumber())
                .subtotal(newOrder.getSubtotal())
                .tax(newOrder.getTax())
                .grandTotal(newOrder.getGrandTotal())
                .paymentMethod(newOrder.getPaymentMethod())
                .items(newOrder.getItems().stream()
                        .map(this::convertToItemResponse)
                        .collect(Collectors.toList()))
                .paymentDetails(newOrder.getPaymentDetails())
                .orderStatus(newOrder.getOrderStatus())
                .paymentStatus(newOrder.getPaymentDetails() != null
                        ? newOrder.getPaymentDetails().getStatus().name()
                        : null)
                .createdAt(newOrder.getCreatedAt())
                .build();
                
    }

    private OrderResponse.OrderItemResponse convertToItemResponse(OrderItemEntity orderItemEntity) {
        return OrderResponse.OrderItemResponse.builder()
                .itemId(orderItemEntity.getItemId())
                .name(orderItemEntity.getName())
                .price(orderItemEntity.getPrice())
                .quantity(orderItemEntity.getQuantity())
                .build();

    }

    @Override
    public void deleteOrder(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        orderEntityRepository.delete(existingOrder);
    }

    @Override
    public List<OrderResponse> getLatestOrders() {
        return orderEntityRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getMyOrders() {
        UserEntity currentUser = getAuthenticatedUser();
        // Filtering happens in the database query (findByUser_Id...), not by fetching
        // every order and filtering in application code.
        return orderEntityRepository.findByUser_IdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public OrderResponse verifyPayment(PaymentVerificationRequest request) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderId(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // Ownership first: a caller must not be able to probe another user's order status or
        // payment state by watching which error comes back.
        UserEntity currentUser = getAuthenticatedUser();
        if (existingOrder.getUser() == null || !existingOrder.getUser().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You are not authorized to verify payment for this order");
        }

        // A Razorpay order must already have been created for this local order (see
        // RazorpayServiceImpl.createOrder); without a stored id there is nothing trustworthy
        // to match the client's razorpay_order_id against.
        PaymentDetails paymentDetails = existingOrder.getPaymentDetails();
        if (paymentDetails == null || paymentDetails.getRazorpayOrderId() == null) {
            throw new IllegalStateException("No Razorpay order has been created for this order");
        }

        // Idempotency: replaying the exact same successful verification returns the order as it
        // already stands. Nothing is re-saved, so payment state cannot be duplicated or
        // corrupted by a retry, a double-submit, or a refreshed checkout page. A *different*
        // order/payment pair against an already-PAID order is still rejected below.
        if (existingOrder.getOrderStatus() == OrderStatus.PAID) {
            boolean sameRazorpayOrder = paymentDetails.getRazorpayOrderId().equals(request.getRazorpayOrderId());
            boolean samePayment = Objects.equals(paymentDetails.getRazorpayPaymentId(), request.getRazorpayPaymentId());
            if (sameRazorpayOrder && samePayment) {
                return convertToResponse(existingOrder);
            }
        }

        // Guard: only PENDING_PAYMENT orders can transition to PAID.
        // Prevents invalid transitions like CANCELLED -> PAID or PAYMENT_FAILED -> PAID.
        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot verify payment for order in status: " + existingOrder.getOrderStatus());
        }

        // The payment being verified must be for the Razorpay order this local order was
        // actually tied to - otherwise a genuinely-signed payment for a cheap order could be
        // replayed to settle an expensive one.
        if (!paymentDetails.getRazorpayOrderId().equals(request.getRazorpayOrderId())) {
            throw new IllegalArgumentException("Razorpay order ID does not match this order");
        }

        // Real cryptographic verification against the server-side key secret. On failure the
        // order is left untouched in PENDING_PAYMENT - it is never marked PAID.
        if (!razorpayService.verifyPaymentSignature(request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature())) {
            throw new RuntimeException("Payment verification failed");
        }

        paymentDetails.setRazorpayPaymentId(request.getRazorpayPaymentId());
        paymentDetails.setRazorpaySignature(request.getRazorpaySignature());
        paymentDetails.setStatus(PaymentDetails.PaymentStatus.COMPLETED);

        existingOrder.setOrderStatus(OrderStatus.PAID);

        existingOrder = orderEntityRepository.save(existingOrder);
        return convertToResponse(existingOrder);

    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // Only the order's owner may cancel.
        UserEntity currentUser = getAuthenticatedUser();
        if (existingOrder.getUser() == null || !existingOrder.getUser().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You are not authorized to cancel this order");
        }

        // Only PENDING_PAYMENT orders can be cancelled.
        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot cancel order in status: " + existingOrder.getOrderStatus());
        }

        existingOrder.setOrderStatus(OrderStatus.CANCELLED);
        existingOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        existingOrder = orderEntityRepository.save(existingOrder);
        return convertToResponse(existingOrder);
    }

    @Override
    public OrderResponse failPayment(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // Only the order's owner may mark payment as failed.
        UserEntity currentUser = getAuthenticatedUser();
        if (existingOrder.getUser() == null || !existingOrder.getUser().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You are not authorized to update this order");
        }

        // Only PENDING_PAYMENT orders can transition to PAYMENT_FAILED.
        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot fail payment for order in status: " + existingOrder.getOrderStatus());
        }

        existingOrder.setOrderStatus(OrderStatus.PAYMENT_FAILED);
        existingOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        existingOrder = orderEntityRepository.save(existingOrder);
        return convertToResponse(existingOrder);
    }

    @Override
    public Double sumSalesByDate(LocalDate date) {
        return orderEntityRepository.sumSalesByDate(date);
    }

    @Override
    public Long countByOrderDate(LocalDate date) {
        return orderEntityRepository.countByOrderDate(date);
    }

    @Override
    public List<OrderResponse> findRecentOrders() {
        return orderEntityRepository.findRecentOrders(PageRequest.of(0, 5))
                .stream()
                .map(orderEntity -> convertToResponse(orderEntity))
                .collect(Collectors.toList());
    }

}
