package in.vedchangani.billingsoftware.io;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Jakarta Bean Validation constraints declared on the request DTOs: an empty cart,
 * an invalid quantity, a blank itemId, an invalid phone number, and a bare-bones invalid payment
 * verification payload are all rejected before a controller/service ever sees them.
 */
class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void orderRequest_rejectsEmptyCart() {
        OrderRequest request = OrderRequest.builder()
                .paymentMethod("CASH")
                .cartItems(List.of())
                .build();

        Set<ConstraintViolation<OrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void orderRequest_rejectsInvalidQuantity() {
        OrderRequest request = OrderRequest.builder()
                .paymentMethod("CASH")
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 0)))
                .build();

        Set<ConstraintViolation<OrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void orderRequest_rejectsBlankItemId() {
        OrderRequest request = OrderRequest.builder()
                .paymentMethod("CASH")
                .cartItems(List.of(new OrderRequest.OrderItemRequest(" ", 1)))
                .build();

        Set<ConstraintViolation<OrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void posOrderRequest_rejectsInvalidPhoneNumber() {
        // ONLINE orders no longer carry a phone number (it comes from the account); the POS
        // billing phone is still validated.
        PosOrderRequest request = PosOrderRequest.builder()
                .customerName("Walk-in Customer")
                .phoneNumber("not-a-phone")
                .paymentMethod("CASH")
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 1)))
                .build();

        Set<ConstraintViolation<PosOrderRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void orderRequest_acceptsAValidRequest() {
        OrderRequest request = OrderRequest.builder()
                .paymentMethod("CASH")
                .cartItems(List.of(new OrderRequest.OrderItemRequest("ITEM1", 1)))
                .build();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void paymentVerificationRequest_rejectsMissingFields() {
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        // orderId only - the rest of the Razorpay verification payload is missing.
        request.setOrderId("ORD1");

        Set<ConstraintViolation<PaymentVerificationRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void cashierCreateRequest_rejectsInvalidEmailAndShortPassword() {
        CashierCreateRequest request = CashierCreateRequest.builder()
                .name("Alice")
                .email("not-an-email")
                .mobile("9876543210")
                .password("123")
                .build();

        Set<ConstraintViolation<CashierCreateRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void cashierCreateRequest_requiresEveryField() {
        Set<ConstraintViolation<CashierCreateRequest>> violations =
                validator.validate(CashierCreateRequest.builder().build());
        assertTrue(violations.size() >= 4);
    }

    @Test
    void cashierCreateRequest_acceptsAValidRequest() {
        CashierCreateRequest request = CashierCreateRequest.builder()
                .name("Alice")
                .email("alice@example.com")
                .mobile("9876543210")
                .password("password123")
                .build();

        assertTrue(validator.validate(request).isEmpty());
    }

    private ItemRequest.ItemRequestBuilder aValidItemRequestBuilder() {
        return ItemRequest.builder()
                .name("Burger")
                .price(java.math.BigDecimal.valueOf(50))
                .categoryId("CAT1")
                .stockQuantity(10);
    }

    @Test
    void itemRequest_acceptsValidStockQuantity() {
        ItemRequest request = aValidItemRequestBuilder().stockQuantity(0).build();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void itemRequest_rejectsMissingStockQuantity() {
        ItemRequest request = aValidItemRequestBuilder().stockQuantity(null).build();

        Set<ConstraintViolation<ItemRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void itemRequest_rejectsNegativeStockQuantity() {
        ItemRequest request = aValidItemRequestBuilder().stockQuantity(-1).build();

        Set<ConstraintViolation<ItemRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void stockAdjustmentRequest_rejectsZeroDelta() {
        StockAdjustmentRequest request = StockAdjustmentRequest.builder().delta(0).build();

        Set<ConstraintViolation<StockAdjustmentRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    void stockAdjustmentRequest_acceptsPositiveOrNegativeNonZeroDelta() {
        StockAdjustmentRequest positive = StockAdjustmentRequest.builder().delta(5).build();
        StockAdjustmentRequest negative = StockAdjustmentRequest.builder().delta(-5).build();

        assertTrue(validator.validate(positive).isEmpty());
        assertTrue(validator.validate(negative).isEmpty());
    }
}
