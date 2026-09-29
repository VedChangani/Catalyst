package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.ItemEntity;
import in.vedchangani.billingsoftware.entity.OrderEntity;
import in.vedchangani.billingsoftware.entity.OrderItemEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ConflictException;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.*;
import in.vedchangani.billingsoftware.repository.ItemRepository;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.OrderSpecifications;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.RazorpayService;
import in.vedchangani.billingsoftware.util.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    static final Duration RESERVATION_TIMEOUT = Duration.ofMinutes(30);

    private static final String STOCK_UNAVAILABLE_MESSAGE = "Insufficient stock or item is unavailable";

    private final OrderEntityRepository orderEntityRepository;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final RazorpayService razorpayService;
    private final AuditService auditService;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        return createOrderInternal(SalesChannel.ONLINE, null, null,
                null, request.getPaymentMethod(), request.getCartItems(), null).getOrder();
    }

    @Override
    @Transactional
    public OrderResponse createPosOrder(PosOrderRequest request) {
        return createOrderInternal(SalesChannel.POS, request.getCustomerUserId(), request.getCustomerName(),
                request.getPhoneNumber(), request.getPaymentMethod(), request.getCartItems(), null).getOrder();
    }

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public OrderCreationResult createOrder(OrderRequest request, String idempotencyKey) {
        return createIdempotently(idempotencyKey, () -> createOrderInternal(SalesChannel.ONLINE, null,
                null, null, request.getPaymentMethod(),
                request.getCartItems(), idempotencyKey));
    }

    @Override
    public OrderCreationResult createPosOrder(PosOrderRequest request, String idempotencyKey) {
        return createIdempotently(idempotencyKey, () -> createOrderInternal(SalesChannel.POS,
                request.getCustomerUserId(), request.getCustomerName(), request.getPhoneNumber(),
                request.getPaymentMethod(), request.getCartItems(), idempotencyKey));
    }

    private OrderCreationResult createIdempotently(String idempotencyKey, Supplier<OrderCreationResult> attempt) {
        try {
            return transactionTemplate.execute(status -> attempt.get());
        } catch (KeyReuseConflictException ex) {
            throw ex;
        } catch (DataIntegrityViolationException | ConflictException ex) {
            if (idempotencyKey != null && orderEntityRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
                log.info("Order create raced with another request holding the same Idempotency-Key; replaying");
                return transactionTemplate.execute(status -> attempt.get());
            }
            throw ex;
        }
    }

    private static final class KeyReuseConflictException extends ConflictException {
        KeyReuseConflictException() {
            super("This Idempotency-Key was already used for a different request");
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1-64 characters: letters, digits, '.', '_', ':' or '-'");
        }
    }

    private static String requestFingerprint(SalesChannel channel, UserEntity actor, String customerUserId,
                                             String customerName, String phoneNumber, String paymentMethod,
                                             Map<String, Integer> quantities) {
        StringBuilder canonical = new StringBuilder();
        appendPart(canonical, channel.name());
        appendPart(canonical, String.valueOf(actor.getId()));
        appendPart(canonical, customerUserId == null ? "" : customerUserId.trim());
        appendPart(canonical, customerName == null ? "" : customerName.trim());
        appendPart(canonical, phoneNumber == null ? "" : phoneNumber.trim());
        appendPart(canonical, paymentMethod == null ? "" : paymentMethod.trim());
        new TreeMap<>(quantities).forEach((itemId, quantity) -> {
            appendPart(canonical, itemId);
            appendPart(canonical, String.valueOf(quantity));
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void appendPart(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value).append(';');
    }

    private OrderCreationResult replayExisting(OrderEntity existing, SalesChannel channel, UserEntity actor,
                                               String fingerprint) {
        UserEntity owner = channel == SalesChannel.ONLINE ? existing.getUser() : existing.getCreatedBy();
        boolean sameActor = existing.getSalesChannel() == channel
                && owner != null && owner.getId().equals(actor.getId());
        if (!sameActor || !fingerprint.equals(existing.getIdempotencyFingerprint())) {
            throw new KeyReuseConflictException();
        }
        return new OrderCreationResult(
                channel == SalesChannel.POS ? convertToStaffResponse(existing) : convertToResponse(existing), true);
    }

    private OrderCreationResult createOrderInternal(SalesChannel channel, String customerUserId,
                                                    String customerName, String phoneNumber,
                                                    String paymentMethodName,
                                                    List<OrderRequest.OrderItemRequest> cartItems,
                                                    String idempotencyKey) {
        if (cartItems == null || cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        Map<String, Integer> requestedQuantities = aggregateCartQuantities(cartItems);

        UserEntity actor = null;
        String fingerprint = null;
        if (idempotencyKey != null) {
            validateIdempotencyKey(idempotencyKey);
            actor = getAuthenticatedUser();
            if (channel == SalesChannel.POS) {
                requireStaff(actor);
            }
            fingerprint = requestFingerprint(channel, actor, customerUserId, customerName, phoneNumber,
                    paymentMethodName, requestedQuantities);
            Optional<OrderEntity> existing = orderEntityRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replayExisting(existing.get(), channel, actor, fingerprint);
            }
        }

        Map<String, ItemEntity> itemsById = new LinkedHashMap<>();
        for (String itemId : requestedQuantities.keySet()) {
            ItemEntity item = itemRepository.findByItemId(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemId));
            if (!Boolean.TRUE.equals(item.getActive())) {
                throw new ConflictException(stockUnavailableMessage(item.getName()));
            }
            itemsById.put(itemId, item);
        }

        List<OrderItemEntity> orderItems = new ArrayList<>();
        requestedQuantities.forEach((itemId, quantity) ->
                orderItems.add(toOrderItemSnapshot(itemsById.get(itemId), quantity)));

        BigDecimal subtotal = orderItems.stream()
                .map(item -> Money.lineTotal(item.getPrice(), item.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = Money.tax(subtotal);
        BigDecimal grandTotal = subtotal.add(tax);

        PaymentMethod paymentMethod = PaymentMethod.valueOf(paymentMethodName);
        boolean isCash = paymentMethod == PaymentMethod.CASH;

        if (actor == null) {
            actor = getAuthenticatedUser();
        }
        UserEntity customer;
        UserEntity createdBy;
        if (channel == SalesChannel.ONLINE) {
            customer = actor;
            createdBy = null;
            customerName = actor.getName() == null ? null : actor.getName().trim();
            phoneNumber = actor.getMobile() == null ? null : actor.getMobile().trim();
            if (customerName == null || customerName.isEmpty() || phoneNumber == null || phoneNumber.isEmpty()) {
                throw new IllegalArgumentException(
                        "Your account is missing a name or mobile number. Complete your profile before placing an order.");
            }
        } else {
            requireStaff(actor);
            customer = resolvePosCustomer(customerUserId);
            createdBy = actor;
            if (customer != null) {
                if (customerName == null || customerName.isBlank()) {
                    customerName = customer.getName();
                }
                if (phoneNumber == null || phoneNumber.isBlank()) {
                    phoneNumber = customer.getMobile();
                }
            }
        }

        Map<String, Integer> expiredReleases = expireStaleReservations(requestedQuantities.keySet());
        applyInventoryMutations(expiredReleases, requestedQuantities, itemsById, isCash);

        OrderEntity newOrder = OrderEntity.builder()
                .customerName(customerName)
                .phoneNumber(phoneNumber)
                .subtotal(subtotal)
                .tax(tax)
                .grandTotal(grandTotal)
                .paymentMethod(paymentMethod)
                .inventoryReserved(!isCash)
                .idempotencyKey(idempotencyKey)
                .idempotencyFingerprint(fingerprint)
                .build();
        newOrder.setUser(customer);
        newOrder.setCreatedBy(createdBy);
        newOrder.setSalesChannel(channel);

        PaymentDetails paymentDetails = new PaymentDetails();
        if (isCash) {
            paymentDetails.setStatus(PaymentDetails.PaymentStatus.COMPLETED);
            newOrder.setOrderStatus(OrderStatus.PAID);
        } else {
            paymentDetails.setStatus(PaymentDetails.PaymentStatus.PENDING);
            newOrder.setOrderStatus(OrderStatus.PENDING_PAYMENT);
        }
        newOrder.setPaymentDetails(paymentDetails);

        newOrder.setItems(orderItems);

        newOrder = orderEntityRepository.save(newOrder);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("salesChannel", channel);
        details.put("paymentMethod", paymentMethod);
        details.put("orderStatus", newOrder.getOrderStatus());
        details.put("grandTotal", grandTotal);
        if (channel == SalesChannel.POS) {
            details.put("customer", customer == null ? "WALK_IN" : "REGISTERED");
        }
        auditService.recordFor(actor,
                channel == SalesChannel.POS ? AuditAction.POS_ORDER_CREATED : AuditAction.ONLINE_ORDER_CREATED,
                AuditTargetType.ORDER, newOrder.getOrderId(), details);

        return new OrderCreationResult(
                channel == SalesChannel.POS ? convertToStaffResponse(newOrder) : convertToResponse(newOrder), false);
    }

    private void requireStaff(UserEntity actor) {
        if (!"ROLE_CASHIER".equals(actor.getRole())) {
            throw new AccessDeniedException("Only cashiers can create POS orders");
        }
    }

    private UserEntity resolvePosCustomer(String customerUserId) {
        if (customerUserId == null) {
            return null;
        }
        if (customerUserId.isBlank()) {
            throw new IllegalArgumentException("customerUserId must not be blank");
        }
        return userRepository.findByUserId(customerUserId)
                .filter(found -> "ROLE_USER".equals(found.getRole()))
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    private Map<String, Integer> aggregateCartQuantities(List<OrderRequest.OrderItemRequest> cartItems) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (OrderRequest.OrderItemRequest line : cartItems) {
            if (line.getQuantity() == null || line.getQuantity() <= 0) {
                throw new IllegalArgumentException(
                        "Quantity must be greater than 0 for item: " + line.getItemId());
            }
            quantities.merge(line.getItemId(), line.getQuantity(), (existing, added) -> {
                try {
                    return Math.addExact(existing, added);
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException("Quantity is too large for item: " + line.getItemId());
                }
            });
        }
        return quantities;
    }

    private Map<String, Integer> expireStaleReservations(Collection<String> itemIds) {
        LocalDateTime cutoff = LocalDateTime.now().minus(RESERVATION_TIMEOUT);
        List<OrderEntity> staleOrders = orderEntityRepository.findStaleReservedOrdersContainingItems(
                OrderStatus.PENDING_PAYMENT, cutoff, itemIds);
        if (staleOrders.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<OrderItemEntity>> linesByOrderId = new TreeMap<>();
        Map<Long, String> publicIdById = new HashMap<>();
        for (OrderEntity staleOrder : staleOrders) {
            linesByOrderId.put(staleOrder.getId(), new ArrayList<>(staleOrder.getItems()));
            publicIdById.put(staleOrder.getId(), staleOrder.getOrderId());
        }

        Map<String, Integer> releases = new HashMap<>();
        linesByOrderId.forEach((orderId, lines) -> {
            int claimed = orderEntityRepository.claimStaleReservationForExpiry(orderId,
                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED, PaymentDetails.PaymentStatus.FAILED);
            if (claimed == 0) {
                return;
            }
            for (OrderItemEntity line : lines) {
                releases.merge(line.getItemId(), line.getQuantity(), Integer::sum);
            }
            auditService.recordSystem(AuditAction.PAYMENT_FAILED, AuditTargetType.ORDER, publicIdById.get(orderId),
                    Map.of("reason", "RESERVATION_EXPIRED"));
        });
        return releases;
    }

    private void applyInventoryMutations(Map<String, Integer> releases,
                                         Map<String, Integer> reservations,
                                         Map<String, ItemEntity> itemsById,
                                         boolean commitImmediately) {
        SortedSet<String> itemIds = new TreeSet<>(releases.keySet());
        itemIds.addAll(reservations.keySet());

        for (String itemId : itemIds) {
            Integer releaseQuantity = releases.get(itemId);
            if (releaseQuantity != null && itemRepository.releaseReservedStock(itemId, releaseQuantity) == 0) {
                log.error("Could not release expired reservation of {} unit(s) for item {}: reservedQuantity is lower than recorded",
                        releaseQuantity, itemId);
                throw new ConflictException(
                        "Inventory could not be reconciled for this order. Please try again or contact an administrator.");
            }

            Integer quantity = reservations.get(itemId);
            if (quantity == null) {
                continue;
            }
            String itemName = itemsById.get(itemId).getName();
            if (itemRepository.reserveStock(itemId, quantity) == 0) {
                throw new ConflictException(stockUnavailableMessage(itemName));
            }
            if (commitImmediately && itemRepository.commitReservedStock(itemId, quantity) == 0) {
                throw new ConflictException("Unable to confirm stock for: " + itemName + ". Please try again.");
            }
        }
    }

    private String stockUnavailableMessage(String itemName) {
        return STOCK_UNAVAILABLE_MESSAGE + ": " + itemName;
    }

    private UserEntity getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("No authenticated user found");
        }
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found: " + email));
    }

    private OrderItemEntity toOrderItemSnapshot(ItemEntity item, int quantity) {
        return OrderItemEntity.builder()
                .itemId(item.getItemId())
                .name(item.getName())
                .price(item.getPrice())
                .quantity(quantity)
                .build();
    }

    private OrderResponse convertToResponse(OrderEntity newOrder) {
        return toResponseBuilder(newOrder).build();
    }

    private OrderResponse convertToStaffResponse(OrderEntity order) {
        UserEntity staff = order.getCreatedBy();
        UserEntity customer = order.getUser();
        return toResponseBuilder(order)
                .customer(customer == null ? null : CustomerSummaryResponse.builder()
                        .userId(customer.getUserId())
                        .name(customer.getName())
                        .email(customer.getEmail())
                        .build())
                .createdBy(staff == null ? null : OrderResponse.StaffSummary.builder()
                        .userId(staff.getUserId())
                        .name(staff.getName())
                        .build())
                .build();
    }

    private OrderResponse.OrderResponseBuilder toResponseBuilder(OrderEntity newOrder) {
        return OrderResponse.builder()
                .orderId(newOrder.getOrderId())
                .customerName(newOrder.getCustomerName())
                .phoneNumber(newOrder.getPhoneNumber())
                .subtotal(Money.forResponse(newOrder.getSubtotal()))
                .tax(Money.forResponse(newOrder.getTax()))
                .grandTotal(Money.forResponse(newOrder.getGrandTotal()))
                .paymentMethod(newOrder.getPaymentMethod())
                .items(newOrder.getItems().stream()
                        .map(this::convertToItemResponse)
                        .collect(Collectors.toList()))
                .paymentDetails(toPaymentSummary(newOrder.getPaymentDetails()))
                .orderStatus(newOrder.getOrderStatus())
                .paymentStatus(newOrder.getPaymentDetails() != null
                        ? newOrder.getPaymentDetails().getStatus().name()
                        : null)
                .salesChannel(newOrder.getSalesChannel())
                .createdAt(newOrder.getCreatedAt());
    }

    private OrderResponse.PaymentSummary toPaymentSummary(PaymentDetails details) {
        return details == null ? null : OrderResponse.PaymentSummary.builder()
                .razorpayOrderId(details.getRazorpayOrderId())
                .razorpayPaymentId(details.getRazorpayPaymentId())
                .status(details.getStatus())
                .paidAt(details.getPaidAt())
                .build();
    }

    private OrderResponse.OrderItemResponse convertToItemResponse(OrderItemEntity orderItemEntity) {
        return OrderResponse.OrderItemResponse.builder()
                .itemId(orderItemEntity.getItemId())
                .name(orderItemEntity.getName())
                .price(Money.forResponse(orderItemEntity.getPrice()))
                .quantity(orderItemEntity.getQuantity())
                .lineTotal(orderItemEntity.getPrice() == null || orderItemEntity.getQuantity() == null
                        ? null
                        : Money.forResponse(Money.lineTotal(orderItemEntity.getPrice(), orderItemEntity.getQuantity())))
                .build();

    }

    private static Map<String, Object> lifecycleDetails(OrderEntity order) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (order.getSalesChannel() != null) details.put("salesChannel", order.getSalesChannel());
        if (order.getPaymentMethod() != null) details.put("paymentMethod", order.getPaymentMethod());
        if (order.getOrderStatus() != null) details.put("orderStatus", order.getOrderStatus());
        return details;
    }

    @Override
    public List<OrderResponse> getLatestOrders() {
        return orderEntityRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::convertToStaffResponse)
                .collect(Collectors.toList());
    }

    static final int DEFAULT_ADMIN_PAGE_SIZE = 20;
    static final int MAX_ADMIN_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 100;
    private static final Map<String, String> ADMIN_SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "grandTotal", "grandTotal",
            "orderId", "orderId");

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminOrderSummaryResponse> getAdminOrders(AdminOrderQuery query) {
        int page = query.getPage() == null ? 0 : query.getPage();
        int size = query.getSize() == null ? DEFAULT_ADMIN_PAGE_SIZE : query.getSize();
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_ADMIN_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_ADMIN_PAGE_SIZE);
        }
        if (query.getSearch() != null && query.getSearch().length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search must be at most " + MAX_SEARCH_LENGTH + " characters");
        }
        if (query.getMinAmount() != null && query.getMinAmount().signum() < 0) {
            throw new IllegalArgumentException("minAmount must be 0 or greater");
        }
        if (query.getMaxAmount() != null && query.getMaxAmount().signum() < 0) {
            throw new IllegalArgumentException("maxAmount must be 0 or greater");
        }
        if (query.getMinAmount() != null && query.getMaxAmount() != null
                && query.getMinAmount().compareTo(query.getMaxAmount()) > 0) {
            throw new IllegalArgumentException("minAmount must not be greater than maxAmount");
        }
        if (query.getDateFrom() != null && query.getDateTo() != null
                && query.getDateFrom().isAfter(query.getDateTo())) {
            throw new IllegalArgumentException("dateFrom must not be after dateTo");
        }

        Page<OrderEntity> result = orderEntityRepository.findAll(
                OrderSpecifications.matching(query),
                PageRequest.of(page, size, parseAdminSort(query.getSort())));

        return PagedResponse.<AdminOrderSummaryResponse>builder()
                .content(result.getContent().stream().map(this::toAdminSummary).collect(Collectors.toList()))
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .first(result.isFirst())
                .last(result.isLast())
                .build();
    }

    private Sort parseAdminSort(String sort) {
        String field = "createdAt";
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", -1);
            if (parts.length > 2) {
                throw new IllegalArgumentException("Invalid sort parameter");
            }
            field = parts[0].trim();
            if (parts.length == 2) {
                direction = Sort.Direction.fromOptionalString(parts[1].trim())
                        .orElseThrow(() -> new IllegalArgumentException("Sort direction must be asc or desc"));
            }
        }
        String property = ADMIN_SORT_FIELDS.get(field);
        if (property == null) {
            throw new IllegalArgumentException("Unsupported sort field. Allowed: " + String.join(", ", new TreeSet<>(ADMIN_SORT_FIELDS.keySet())));
        }
        return Sort.by(direction, property).and(Sort.by(direction, "id"));
    }

    private AdminOrderSummaryResponse toAdminSummary(OrderEntity order) {
        UserEntity customer = order.getUser();
        UserEntity staff = order.getCreatedBy();
        PaymentDetails payment = order.getPaymentDetails();
        return AdminOrderSummaryResponse.builder()
                .orderId(order.getOrderId())
                .createdAt(order.getCreatedAt())
                .salesChannel(order.getSalesChannel())
                .customerName(order.getCustomerName())
                .phoneNumber(order.getPhoneNumber())
                .subtotal(Money.forResponse(order.getSubtotal()))
                .tax(Money.forResponse(order.getTax()))
                .grandTotal(Money.forResponse(order.getGrandTotal()))
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(payment == null ? null : payment.getStatus())
                .orderStatus(order.getOrderStatus())
                .customer(customer == null ? null : CustomerSummaryResponse.builder()
                        .userId(customer.getUserId())
                        .name(customer.getName())
                        .email(customer.getEmail())
                        .build())
                .createdBy(staff == null ? null : OrderResponse.StaffSummary.builder()
                        .userId(staff.getUserId())
                        .name(staff.getName())
                        .build())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders() {
        UserEntity currentUser = getAuthenticatedUser();
        return orderEntityRepository.findByUser_IdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getMySales() {
        UserEntity cashier = getAuthenticatedUser();
        requireStaff(cashier);
        return orderEntityRepository.findByCreatedBy_IdAndSalesChannelOrderByCreatedAtDesc(cashier.getId(), SalesChannel.POS)
                .stream()
                .map(this::convertToStaffResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getMyOrder(String orderId) {
        OrderEntity order = orderEntityRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        UserEntity currentUser = getAuthenticatedUser();
        if ("ROLE_CASHIER".equals(currentUser.getRole())) {
            boolean enteredByCaller = order.getSalesChannel() == SalesChannel.POS
                    && order.getCreatedBy() != null
                    && order.getCreatedBy().getId().equals(currentUser.getId());
            if (!enteredByCaller) {
                throw new AccessDeniedException("You are not authorized to view this order");
            }
            return convertToStaffResponse(order);
        }
        if (order.getUser() == null || !order.getUser().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You are not authorized to view this order");
        }
        return convertToResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse verifyPayment(PaymentVerificationRequest request) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderIdForUpdate(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        UserEntity currentUser = getAuthenticatedUser();
        if (!existingOrder.canBeManagedBy(currentUser)) {
            throw new AccessDeniedException("You are not authorized to verify payment for this order");
        }

        PaymentDetails paymentDetails = existingOrder.getPaymentDetails();
        if (paymentDetails == null || paymentDetails.getRazorpayOrderId() == null) {
            throw new IllegalStateException("No Razorpay order has been created for this order");
        }

        if (existingOrder.getOrderStatus() == OrderStatus.PAID) {
            boolean sameRazorpayOrder = paymentDetails.getRazorpayOrderId().equals(request.getRazorpayOrderId());
            boolean samePayment = Objects.equals(paymentDetails.getRazorpayPaymentId(), request.getRazorpayPaymentId());
            if (sameRazorpayOrder && samePayment) {
                return convertToResponse(existingOrder);
            }
        }

        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            logLateValidPayment(existingOrder, paymentDetails, request);
            throw new IllegalStateException(
                    "Cannot verify payment for order in status: " + existingOrder.getOrderStatus());
        }

        if (!paymentDetails.getRazorpayOrderId().equals(request.getRazorpayOrderId())) {
            throw new IllegalArgumentException("Razorpay order ID does not match this order");
        }

        if (!razorpayService.verifyPaymentSignature(request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature())) {
            throw new IllegalArgumentException("Payment verification failed");
        }

        commitReservation(existingOrder);

        paymentDetails.setRazorpayPaymentId(request.getRazorpayPaymentId());
        paymentDetails.setRazorpaySignature(request.getRazorpaySignature());
        paymentDetails.setStatus(PaymentDetails.PaymentStatus.COMPLETED);
        paymentDetails.setPaidAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));

        existingOrder.setOrderStatus(OrderStatus.PAID);

        existingOrder = orderEntityRepository.save(existingOrder);
        auditService.recordFor(currentUser, AuditAction.PAYMENT_VERIFIED, AuditTargetType.ORDER,
                existingOrder.getOrderId(), lifecycleDetails(existingOrder));
        return convertToResponse(existingOrder);

    }

    private void logLateValidPayment(OrderEntity order, PaymentDetails stored, PaymentVerificationRequest request) {
        try {
            boolean terminalNotPaid = order.getOrderStatus() == OrderStatus.CANCELLED
                    || order.getOrderStatus() == OrderStatus.PAYMENT_FAILED;
            if (!terminalNotPaid || !stored.getRazorpayOrderId().equals(request.getRazorpayOrderId())) {
                return;
            }
            if (razorpayService.verifyPaymentSignature(request.getRazorpayOrderId(),
                    request.getRazorpayPaymentId(), request.getRazorpaySignature())) {
                log.error("LATE_VALID_PAYMENT orderId={} razorpayOrderId={} razorpayPaymentId={} orderStatus={} - "
                                + "a genuine Razorpay payment arrived for an order that is no longer payable; "
                                + "the order was NOT changed. Manual reconciliation/refund required.",
                        order.getOrderId(), request.getRazorpayOrderId(), request.getRazorpayPaymentId(),
                        order.getOrderStatus());
            }
        } catch (RuntimeException ex) {
            log.warn("Could not evaluate a late payment for order {}", order.getOrderId());
        }
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        UserEntity currentUser = getAuthenticatedUser();
        if (!existingOrder.canBeManagedBy(currentUser)) {
            throw new AccessDeniedException("You are not authorized to cancel this order");
        }

        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot cancel order in status: " + existingOrder.getOrderStatus());
        }

        releaseReservation(existingOrder,
                "Payment cancellation could not be completed because inventory could not be released.");

        existingOrder.setOrderStatus(OrderStatus.CANCELLED);
        existingOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        existingOrder = orderEntityRepository.save(existingOrder);
        auditService.recordFor(currentUser, AuditAction.ORDER_CANCELLED, AuditTargetType.ORDER,
                existingOrder.getOrderId(), lifecycleDetails(existingOrder));
        return convertToResponse(existingOrder);
    }

    @Override
    @Transactional
    public OrderResponse failPayment(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        UserEntity currentUser = getAuthenticatedUser();
        if (!existingOrder.canBeManagedBy(currentUser)) {
            throw new AccessDeniedException("You are not authorized to update this order");
        }

        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot fail payment for order in status: " + existingOrder.getOrderStatus());
        }

        releaseReservation(existingOrder,
                "Payment failure could not be recorded because inventory could not be released.");

        existingOrder.setOrderStatus(OrderStatus.PAYMENT_FAILED);
        existingOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        existingOrder = orderEntityRepository.save(existingOrder);
        auditService.recordFor(currentUser, AuditAction.PAYMENT_FAILED, AuditTargetType.ORDER,
                existingOrder.getOrderId(), lifecycleDetails(existingOrder));
        return convertToResponse(existingOrder);
    }

    private void commitReservation(OrderEntity order) {
        if (!Boolean.TRUE.equals(order.getInventoryReserved())) {
            return;
        }
        reservedQuantitiesByItem(order).forEach((itemId, quantity) -> {
            if (itemRepository.commitReservedStock(itemId, quantity) == 0) {
                log.error("Order {} passed payment verification but {} unit(s) of item {} could not be committed; "
                        + "order left unchanged for manual reconciliation", order.getOrderId(), quantity, itemId);
                throw new ConflictException("Payment could not be completed because inventory could not be finalized.");
            }
        });
        order.setInventoryReserved(false);
    }

    private void releaseReservation(OrderEntity order, String failureMessage) {
        if (!Boolean.TRUE.equals(order.getInventoryReserved())) {
            return;
        }
        reservedQuantitiesByItem(order).forEach((itemId, quantity) -> {
            if (itemRepository.releaseReservedStock(itemId, quantity) == 0) {
                log.error("Could not release {} reserved unit(s) of item {} for order {}; order left unchanged",
                        quantity, itemId, order.getOrderId());
                throw new ConflictException(failureMessage);
            }
        });
        order.setInventoryReserved(false);
    }

    private Map<String, Integer> reservedQuantitiesByItem(OrderEntity order) {
        Map<String, Integer> quantities = new TreeMap<>();
        for (OrderItemEntity line : order.getItems()) {
            quantities.merge(line.getItemId(), line.getQuantity(), Integer::sum);
        }
        return quantities;
    }

    @Override
    public BigDecimal sumSalesByDate(LocalDate date) {
        return Money.zeroIfNull(orderEntityRepository.sumPaidRevenue(date.atStartOfDay(), date.plusDays(1).atStartOfDay()));
    }

    @Override
    public Long countByOrderDate(LocalDate date) {
        return orderEntityRepository.countPaidOrders(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    @Override
    public List<OrderResponse> findRecentOrders() {
        return orderEntityRepository.findRecentOrders(PageRequest.of(0, 5))
                .stream()
                .map(this::convertToStaffResponse)
                .collect(Collectors.toList());
    }

}
