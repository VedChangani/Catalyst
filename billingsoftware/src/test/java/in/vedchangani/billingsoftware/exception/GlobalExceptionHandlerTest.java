package in.vedchangani.billingsoftware.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that GlobalExceptionHandler maps each exception type to the intended HTTP status and
 * never leaks internal detail (e.g. a DataIntegrityViolationException's raw SQL message) into
 * the response body.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest aRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1.0/some/path");
        when(request.getMethod()).thenReturn("POST");
        return request;
    }

    @Test
    void illegalArgument_mapsTo400() {
        ResponseEntity<ErrorResponse> response =
                handler.handleIllegalArgument(new IllegalArgumentException("Cart is empty"), aRequest());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Cart is empty", response.getBody().getMessage());
        assertEquals("/api/v1.0/some/path", response.getBody().getPath());
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    void resourceNotFound_mapsTo404() {
        ResponseEntity<ErrorResponse> response =
                handler.handleNotFound(new ResourceNotFoundException("Order not found"), aRequest());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Order not found", response.getBody().getMessage());
    }

    @Test
    void usernameNotFound_mapsTo404() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUsernameNotFound(new UsernameNotFoundException("User not found"), aRequest());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void conflict_mapsTo409() {
        ResponseEntity<ErrorResponse> response =
                handler.handleConflict(new ConflictException("Category still has items"), aRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Category still has items", response.getBody().getMessage());
    }

    @Test
    void illegalState_mapsTo409() {
        ResponseEntity<ErrorResponse> response =
                handler.handleIllegalState(new IllegalStateException("Cannot cancel order in status: PAID"), aRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void accessDenied_mapsTo403_withGenericMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDenied(new AccessDeniedException("internal detail that should not leak"), aRequest());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("You do not have permission to perform this action", response.getBody().getMessage());
    }

    @Test
    void dataIntegrityViolation_mapsTo409_withoutLeakingSqlDetail() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("could not execute statement; SQL [n/a]; constraint [fk_item_category]"),
                aRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("This action conflicts with existing data", response.getBody().getMessage());
        assertFalse(response.getBody().getMessage().toLowerCase().contains("sql"));
    }

    @Test
    void optimisticLockingFailure_mapsTo409_withGenericMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockingFailure(
                new ObjectOptimisticLockingFailureException("in.vedchangani.billingsoftware.entity.ItemEntity", "ITEM1"),
                aRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Item was modified by another request. Please refresh and try again.", response.getBody().getMessage());
        assertFalse(response.getBody().getMessage().contains("ItemEntity"));
    }

    @Test
    void unexpectedException_mapsTo500_withGenericMessage_neverLeakingTheOriginalMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(new RuntimeException("db password is hunter2"), aRequest());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("An unexpected error occurred", response.getBody().getMessage());
        assertFalse(response.getBody().getMessage().contains("hunter2"));
    }
}
