package in.vedchangani.billingsoftware.exception;

/**
 * Thrown when a request is well-formed but cannot be completed because it conflicts with the
 * current state of the data (e.g. deleting a category that still has items, registering an
 * email that is already taken). Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
