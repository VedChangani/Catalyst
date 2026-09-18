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
import in.vedchangani.billingsoftware.service.OrderService;
import in.vedchangani.billingsoftware.service.RazorpayService;
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

    // Tax rate applied to the server-computed subtotal. Kept in sync with the
    // display-only calculation in the frontend cart summary (1%).
    private static final double TAX_RATE = 0.01;

    // A PENDING_PAYMENT order older than this is treated as abandoned: its reservation is
    // released lazily the next time another order touches one of its items.
    static final Duration RESERVATION_TIMEOUT = Duration.ofMinutes(30);

    private static final String STOCK_UNAVAILABLE_MESSAGE = "Insufficient stock or item is unavailable";

    private final OrderEntityRepository orderEntityRepository;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    // Used only for cryptographic signature verification; the Razorpay key secret stays
    // inside RazorpayServiceImpl and never crosses this boundary.
    private final RazorpayService razorpayService;

    // One transaction: stale-reservation expiry, every stock reservation (and, for CASH, every
    // commit) and the order insert either all commit or all roll back. A conflict on the last
    // cart line therefore undoes the reservations already made for earlier lines - there is no
    // manual compensation anywhere in this method.
    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        return createOrderInternal(SalesChannel.ONLINE, null, request.getCustomerName(),
                request.getPhoneNumber(), request.getPaymentMethod(), request.getCartItems(), null).getOrder();
    }

    // POS entry point. Pricing, tax, inventory reservation/commit, payment handling and
    // persistence are all the shared createOrderInternal below - only who the customer and the
    // creator are differs, and both are resolved on the server.
    @Override
    @Transactional
    public OrderResponse createPosOrder(PosOrderRequest request) {
        return createOrderInternal(SalesChannel.POS, request.getCustomerUserId(), request.getCustomerName(),
                request.getPhoneNumber(), request.getPaymentMethod(), request.getCartItems(), null).getOrder();
    }

    // ---- Idempotent creation (Idempotency-Key) ----

    // Idempotency-Key format: opaque, 1-64 chars of letters, digits and . _ : - (a UUID fits).
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    // Used to run each attempt in its own transaction from OUTSIDE any transaction, so a failed
    // attempt is fully rolled back (stock reservations included) before the retry below runs.
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public OrderCreationResult createOrder(OrderRequest request, String idempotencyKey) {
        return createIdempotently(idempotencyKey, () -> createOrderInternal(SalesChannel.ONLINE, null,
                request.getCustomerName(), request.getPhoneNumber(), request.getPaymentMethod(),
                request.getCartItems(), idempotencyKey));
    }

    @Override
    public OrderCreationResult createPosOrder(PosOrderRequest request, String idempotencyKey) {
        return createIdempotently(idempotencyKey, () -> createOrderInternal(SalesChannel.POS,
                request.getCustomerUserId(), request.getCustomerName(), request.getPhoneNumber(),
                request.getPaymentMethod(), request.getCartItems(), idempotencyKey));
    }

    // Runs one create attempt in a single transaction. Two identical requests can both pass the
    // "key not seen yet" check; the unique index on idempotency_key then lets only one order commit.
    // The loser fails either on that index (DataIntegrityViolationException) or - when the winner
    // already took the last units - on its own stock reservation (ConflictException). In both cases
    // its transaction has already rolled back completely, and if the key's order now exists the
    // right answer is the replay, so the attempt is run once more: it finds the key at the very
    // start and returns the winner's order (or a key-reuse conflict) without touching inventory.
    // If no order holds the key, the failure was unrelated to idempotency and is rethrown as is.
    private OrderCreationResult createIdempotently(String idempotencyKey, Supplier<OrderCreationResult> attempt) {
        try {
            return transactionTemplate.execute(status -> attempt.get());
        } catch (KeyReuseConflictException ex) {
            throw ex; // a definitive answer, not a race: nothing to retry
        } catch (DataIntegrityViolationException | ConflictException ex) {
            if (idempotencyKey != null && orderEntityRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
                log.info("Order create raced with another request holding the same Idempotency-Key; replaying");
                return transactionTemplate.execute(status -> attempt.get());
            }
            throw ex;
        }
    }

    // 409 for a key that already belongs to a different request/actor (mapped like any ConflictException).
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

    // SHA-256 over the normalised LOGICAL request. Every field is length-prefixed, so no value can
    // bleed into its neighbour, and item lines are aggregated and sorted by itemId, so ordering or
    // splitting of the same lines does not change the result. Only server-derived or validated
    // inputs go in: channel (from the endpoint), the authenticated actor's id, the explicit POS
    // customer selection, billing name/phone, payment method and (itemId, quantity) lines.
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

    // The same key with the same actor, channel and logical request returns the existing order as it
    // is NOW (any status). Nothing is created, reserved, committed, released or saved.
    private OrderCreationResult replayExisting(OrderEntity existing, SalesChannel channel, UserEntity actor,
                                               String fingerprint) {
        // ONLINE orders belong to their customer; POS orders are attributed to the staff member who
        // entered them. customerName/phoneNumber play no part in who the actor is.
        UserEntity owner = channel == SalesChannel.ONLINE ? existing.getUser() : existing.getCreatedBy();
        boolean sameActor = existing.getSalesChannel() == channel
                && owner != null && owner.getId().equals(actor.getId());
        if (!sameActor || !fingerprint.equals(existing.getIdempotencyFingerprint())) {
            // one message for both cases: nothing is revealed about the original order
            throw new KeyReuseConflictException();
        }
        return new OrderCreationResult(
                channel == SalesChannel.POS ? convertToStaffResponse(existing) : convertToResponse(existing), true);
    }

    // The channel is fixed by which public method called this, never by request data.
    // customerUserId is only meaningful for POS (an explicit registered-customer selection).
    private OrderCreationResult createOrderInternal(SalesChannel channel, String customerUserId,
                                                    String customerName, String phoneNumber,
                                                    String paymentMethodName,
                                                    List<OrderRequest.OrderItemRequest> cartItems,
                                                    String idempotencyKey) {
        // Cart must contain at least one line item.
        if (cartItems == null || cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        // Duplicate itemIds are collapsed into one line, so each item is priced, reserved and
        // (for CASH) committed exactly once for its total quantity. Insertion order keeps the
        // order-item snapshot in cart order.
        Map<String, Integer> requestedQuantities = aggregateCartQuantities(cartItems);

        // Idempotency runs before ANY inventory work or item lookup: a replay must still succeed
        // when items were deactivated or repriced since the original order.
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

        // Item identity/name/price and the order's monetary totals are never trusted from the
        // client: each cart line is resolved to its authoritative ItemEntity by itemId, and
        // subtotal/tax/grandTotal are computed here from those resolved prices.
        Map<String, ItemEntity> itemsById = new LinkedHashMap<>();
        for (String itemId : requestedQuantities.keySet()) {
            ItemEntity item = itemRepository.findByItemId(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemId));
            // Fast, friendly rejection; reserveStock re-checks active atomically regardless.
            // A legacy item with no active value yet is treated as not sellable.
            if (!Boolean.TRUE.equals(item.getActive())) {
                throw new ConflictException(stockUnavailableMessage(item.getName()));
            }
            itemsById.put(itemId, item);
        }

        List<OrderItemEntity> orderItems = new ArrayList<>();
        requestedQuantities.forEach((itemId, quantity) ->
                orderItems.add(toOrderItemSnapshot(itemsById.get(itemId), quantity)));

        double subtotal = orderItems.stream()
                .mapToDouble(item -> item.getPrice() * item.getQuantity())
                .sum();
        double tax = subtotal * TAX_RATE;
        double grandTotal = subtotal + tax;

        PaymentMethod paymentMethod = PaymentMethod.valueOf(paymentMethodName);
        boolean isCash = paymentMethod == PaymentMethod.CASH;

        // Ownership and creator identity are derived exclusively from the authenticated principal
        // (Spring Security) and, for POS, an explicit customer selection that is validated and
        // resolved here - never from client-supplied createdBy/salesChannel, customerName, or
        // phoneNumber. Resolved before any inventory is touched.
        if (actor == null) {
            actor = getAuthenticatedUser();
        }
        UserEntity customer;
        UserEntity createdBy;
        if (channel == SalesChannel.ONLINE) {
            customer = actor;
            createdBy = null;
        } else {
            requireStaff(actor);
            customer = resolvePosCustomer(customerUserId);
            createdBy = actor;
            if ((customerName == null || customerName.isBlank()) && customer != null) {
                customerName = customer.getName();
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
                // CASH stock is already committed above; UPI stock stays reserved until payment
                // is verified, cancelled, fails, or the reservation expires.
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
        return new OrderCreationResult(
                channel == SalesChannel.POS ? convertToStaffResponse(newOrder) : convertToResponse(newOrder), false);
    }

    private void requireStaff(UserEntity actor) {
        if (!"ROLE_CASHIER".equals(actor.getRole()) && !"ROLE_ADMIN".equals(actor.getRole())) {
            throw new AccessDeniedException("Only cashiers and administrators can create POS orders");
        }
    }

    // null id = walk-in sale. Otherwise the id must identify an existing registered customer
    // (ROLE_USER account); anything else is reported as not found, so the caller learns nothing
    // about staff accounts.
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
            // Quantity must be a positive number.
            if (line.getQuantity() == null || line.getQuantity() <= 0) {
                throw new IllegalArgumentException(
                        "Quantity must be greater than 0 for item: " + line.getItemId());
            }
            // addExact: two huge positive quantities must never wrap into a negative total,
            // which the reserve query would otherwise treat as a stock *release*.
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

    /**
     * Lazily expires abandoned UPI checkouts that hold stock this order needs. There is no
     * scheduler, so this runs on the order-creation path instead: every stale reserved
     * PENDING_PAYMENT order containing one of these items is atomically claimed
     * (PENDING_PAYMENT -> PAYMENT_FAILED, flag cleared) and its reserved quantities are returned
     * for release. Only the request that wins the claim releases an order's stock, so the same
     * reservation can never be released twice. Runs inside createOrder's transaction: if this
     * order later fails, the expiry rolls back too and is simply retried by the next order.
     */
    private Map<String, Integer> expireStaleReservations(Collection<String> itemIds) {
        LocalDateTime cutoff = LocalDateTime.now().minus(RESERVATION_TIMEOUT);
        List<OrderEntity> staleOrders = orderEntityRepository.findStaleReservedOrdersContainingItems(
                OrderStatus.PENDING_PAYMENT, cutoff, itemIds);
        if (staleOrders.isEmpty()) {
            return Map.of();
        }

        // Snapshot every stale order's lines before the first claim: the claim UPDATE clears the
        // persistence context, after which the remaining lazy items collections couldn't load.
        // TreeMap = claims happen in ascending order id, a deterministic lock order.
        Map<Long, List<OrderItemEntity>> linesByOrderId = new TreeMap<>();
        for (OrderEntity staleOrder : staleOrders) {
            linesByOrderId.put(staleOrder.getId(), new ArrayList<>(staleOrder.getItems()));
        }

        Map<String, Integer> releases = new HashMap<>();
        linesByOrderId.forEach((orderId, lines) -> {
            int claimed = orderEntityRepository.claimStaleReservationForExpiry(orderId,
                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED, PaymentDetails.PaymentStatus.FAILED);
            if (claimed == 0) {
                // Already paid, cancelled, failed or expired by another request - its stock is
                // not ours to release.
                return;
            }
            // Every line of the expired order is released, including items not in this cart.
            for (OrderItemEntity line : lines) {
                releases.merge(line.getItemId(), line.getQuantity(), Integer::sum);
            }
        });
        return releases;
    }

    /**
     * Applies every item-row mutation for this order in ascending itemId order: for each item,
     * first release any expired reservation, then reserve (and for CASH immediately commit) this
     * order's quantity. Because every checkout locks item rows in the same global order, two
     * concurrent multi-item orders over overlapping items queue on the first shared row instead
     * of each holding a row the other needs (the classic A->B / B->A deadlock).
     */
    private void applyInventoryMutations(Map<String, Integer> releases,
                                         Map<String, Integer> reservations,
                                         Map<String, ItemEntity> itemsById,
                                         boolean commitImmediately) {
        SortedSet<String> itemIds = new TreeSet<>(releases.keySet());
        itemIds.addAll(reservations.keySet());

        for (String itemId : itemIds) {
            Integer releaseQuantity = releases.get(itemId);
            if (releaseQuantity != null && itemRepository.releaseReservedStock(itemId, releaseQuantity) == 0) {
                // reservedQuantity is lower than what the expired order recorded - the counters
                // are out of sync. Fail the whole order rather than hide it.
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
            // The conditional UPDATE is the concurrency guard: 0 rows = inactive, missing, or
            // not enough available stock at the moment of the update.
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
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found: " + email));
    }

    // Name and price come from the server-side item catalog - never from the request - so a
    // client cannot alter what an item is called or how much it costs.
    private OrderItemEntity toOrderItemSnapshot(ItemEntity item, int quantity) {
        return OrderItemEntity.builder()
                .itemId(item.getItemId())
                .name(item.getName())
                .price(item.getPrice().doubleValue())
                .quantity(quantity)
                .build();
    }

    // Customer-facing shape: no staff identity.
    private OrderResponse convertToResponse(OrderEntity newOrder) {
        return toResponseBuilder(newOrder).build();
    }

    // Staff/admin-facing shape: adds which staff member entered a POS sale.
    private OrderResponse convertToStaffResponse(OrderEntity order) {
        UserEntity staff = order.getCreatedBy();
        return toResponseBuilder(order)
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
                .subtotal(newOrder.getSubtotal())
                .tax(newOrder.getTax())
                .grandTotal(newOrder.getGrandTotal())
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
                .price(orderItemEntity.getPrice())
                .quantity(orderItemEntity.getQuantity())
                .lineTotal(orderItemEntity.getPrice() == null || orderItemEntity.getQuantity() == null
                        ? null
                        : orderItemEntity.getPrice() * orderItemEntity.getQuantity())
                .build();

    }

    @Override
    public void deleteOrder(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Deleting an order that still holds an uncommitted reservation would strand that
        // reservedQuantity forever - nothing would ever be left to release/commit it. The order
        // must first reach PAID (commit), CANCELLED, or PAYMENT_FAILED (release) through the
        // normal lifecycle, which clears this flag.
        if (Boolean.TRUE.equals(existingOrder.getInventoryReserved())) {
            throw new ConflictException(
                    "Cannot delete order " + orderId + ": it still holds an active inventory reservation");
        }

        orderEntityRepository.delete(existingOrder);
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
    // Sortable API names -> entity properties. Anything else is rejected, so a client-supplied
    // string can never become a query path.
    private static final Map<String, String> ADMIN_SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "grandTotal", "grandTotal",
            "orderId", "orderId");

    // ADMIN order management (URL-level ADMIN-only in SecurityConfig). All filtering, sorting and
    // paging happen in one database query (plus its count); the page is mapped to a summary DTO
    // that needs no item lines, so there is no per-order collection load. Customer and creator
    // are fetched with the page in that same query.
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
        if (query.getMinAmount() != null && (!Double.isFinite(query.getMinAmount()) || query.getMinAmount() < 0)) {
            throw new IllegalArgumentException("minAmount must be 0 or greater");
        }
        if (query.getMaxAmount() != null && (!Double.isFinite(query.getMaxAmount()) || query.getMaxAmount() < 0)) {
            throw new IllegalArgumentException("maxAmount must be 0 or greater");
        }
        if (query.getMinAmount() != null && query.getMaxAmount() != null
                && query.getMinAmount() > query.getMaxAmount()) {
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

    // "field" or "field,asc|desc". Newest first by default. The id is appended as a tie-breaker so
    // pages stay stable when many orders share a createdAt/grandTotal value.
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
                .subtotal(order.getSubtotal())
                .tax(order.getTax())
                .grandTotal(order.getGrandTotal())
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

    // The customer's unified purchase history: every order whose `user` is the authenticated
    // customer, ONLINE and POS alike, newest first. Walk-in POS orders (user = null) and other
    // customers' orders never match; createdBy plays no part.
    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders() {
        UserEntity currentUser = getAuthenticatedUser();
        // Filtering happens in the database query (findByUser_Id...), not by fetching
        // every order and filtering in application code.
        return orderEntityRepository.findByUser_IdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    // One order of the authenticated customer's own history. Ownership is `user` only - never
    // customerName, phoneNumber, createdBy or salesChannel. Line items come from the persisted
    // OrderItemEntity snapshot, so later catalog price/name changes cannot alter what is shown.
    @Override
    @Transactional(readOnly = true)
    public OrderResponse getMyOrder(String orderId) {
        OrderEntity order = orderEntityRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        UserEntity currentUser = getAuthenticatedUser();
        if (order.getUser() == null || !order.getUser().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You are not authorized to view this order");
        }
        return convertToResponse(order);
    }

    // One transaction, holding the order's row lock from the first read: the reservation commit
    // and the PAID transition either both happen or neither does. If any commit affects 0 rows,
    // everything rolls back and the order stays exactly as it was (normally PENDING_PAYMENT) -
    // it is never marked PAID, and never marked PAYMENT_FAILED just because the local stock
    // commit failed. The customer may already have been charged; that case is logged for
    // manual reconciliation rather than hidden.
    @Override
    @Transactional
    public OrderResponse verifyPayment(PaymentVerificationRequest request) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderIdForUpdate(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Ownership first: a caller must not be able to probe another user's order status or
        // payment state by watching which error comes back.
        UserEntity currentUser = getAuthenticatedUser();
        if (!existingOrder.canBeManagedBy(currentUser)) {
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
            // The order is never touched here, but a genuinely captured payment that arrives after
            // the order was cancelled / failed / expired must leave a trail for manual reconciliation.
            logLateValidPayment(existingOrder, paymentDetails, request);
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
            throw new IllegalArgumentException("Payment verification failed");
        }

        // Only now, with a cryptographically verified payment, is the reservation turned into a
        // real stock deduction.
        commitReservation(existingOrder);

        paymentDetails.setRazorpayPaymentId(request.getRazorpayPaymentId());
        paymentDetails.setRazorpaySignature(request.getRazorpaySignature());
        paymentDetails.setStatus(PaymentDetails.PaymentStatus.COMPLETED);
        // Microsecond precision = what the database column stores, so the response matches later reads.
        paymentDetails.setPaidAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));

        existingOrder.setOrderStatus(OrderStatus.PAID);

        existingOrder = orderEntityRepository.save(existingOrder);
        return convertToResponse(existingOrder);

    }

    // Diagnostic only - never changes the order, inventory or the response the client sees. Called
    // after authorisation and after the stored Razorpay order id was confirmed, and it logs ONLY
    // when the backend signature check itself passes, so an unverified request can never produce a
    // "valid late payment" event. The signature, the secret and the raw payload are never logged.
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
            // diagnostics must never alter the normal conflict response
            log.warn("Could not evaluate a late payment for order {}", order.getOrderId());
        }
    }

    // Same shape as verifyPayment: locked read, guards, release every reserved line, then the
    // transition - all in one transaction, so a failed release leaves the order PENDING_PAYMENT
    // with every earlier release in this attempt rolled back.
    @Override
    @Transactional
    public OrderResponse cancelOrder(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Only the order's owner (or, for a POS order, the staff member who entered it) may cancel.
        UserEntity currentUser = getAuthenticatedUser();
        if (!existingOrder.canBeManagedBy(currentUser)) {
            throw new AccessDeniedException("You are not authorized to cancel this order");
        }

        // Only PENDING_PAYMENT orders can be cancelled. This is also what stops a repeated
        // cancellation from releasing the same reservation twice.
        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot cancel order in status: " + existingOrder.getOrderStatus());
        }

        releaseReservation(existingOrder,
                "Payment cancellation could not be completed because inventory could not be released.");

        existingOrder.setOrderStatus(OrderStatus.CANCELLED);
        existingOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        existingOrder = orderEntityRepository.save(existingOrder);
        return convertToResponse(existingOrder);
    }

    @Override
    @Transactional
    public OrderResponse failPayment(String orderId) {
        OrderEntity existingOrder = orderEntityRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Only the order's owner (or, for a POS order, its creator) may mark payment as failed.
        UserEntity currentUser = getAuthenticatedUser();
        if (!existingOrder.canBeManagedBy(currentUser)) {
            throw new AccessDeniedException("You are not authorized to update this order");
        }

        // Only PENDING_PAYMENT orders can transition to PAYMENT_FAILED - which also stops a
        // repeated call from releasing the same reservation twice.
        if (existingOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot fail payment for order in status: " + existingOrder.getOrderStatus());
        }

        releaseReservation(existingOrder,
                "Payment failure could not be recorded because inventory could not be released.");

        existingOrder.setOrderStatus(OrderStatus.PAYMENT_FAILED);
        existingOrder.getPaymentDetails().setStatus(PaymentDetails.PaymentStatus.FAILED);

        existingOrder = orderEntityRepository.save(existingOrder);
        return convertToResponse(existingOrder);
    }

    // Turns this order's reservation into a real stock deduction, one aggregated line per item in
    // ascending itemId order (the same lock order createOrder uses). Orders that hold no
    // reservation - CASH (committed at creation) and legacy orders from before inventory tracking
    // (NULL flag) - have nothing to commit.
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

    // Read fully before the first stock UPDATE: those UPDATEs clear the persistence context, after
    // which the order's lazy items collection could no longer be loaded.
    private Map<String, Integer> reservedQuantitiesByItem(OrderEntity order) {
        Map<String, Integer> quantities = new TreeMap<>();
        for (OrderItemEntity line : order.getItems()) {
            quantities.merge(line.getItemId(), line.getQuantity(), Integer::sum);
        }
        return quantities;
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
                .map(this::convertToStaffResponse)
                .collect(Collectors.toList());
    }

}
