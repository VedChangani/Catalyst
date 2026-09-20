package in.vedchangani.billingsoftware.exception;

/**
 * Thrown when a requested resource (order, item, category, user, ...) does not exist.
 * Mapped to HTTP 404 by {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
