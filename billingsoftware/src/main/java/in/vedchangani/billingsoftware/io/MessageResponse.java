package in.vedchangani.billingsoftware.io;

/** A plain {"message": "..."} body for endpoints that return only a status message. */
public record MessageResponse(String message) {
}
