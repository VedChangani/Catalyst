package in.vedchangani.billingsoftware.util;

/**
 * Builds the public URL of an uploaded image from the configured base URL
 * (app.uploads.public-base-url / APP_UPLOADS_PUBLIC_BASE_URL), so no host is hardcoded in code.
 */
public final class UploadUrls {

    private UploadUrls() {
    }

    // The local directory uploads are written to and served from (app.uploads.dir / APP_UPLOADS_DIR,
    // default "uploads" relative to the working directory). Tests point it at a throwaway directory.
    public static java.nio.file.Path directory(String configuredDir) {
        if (configuredDir == null || configuredDir.isBlank()) {
            throw new IllegalStateException("app.uploads.dir (APP_UPLOADS_DIR) is not configured");
        }
        return java.nio.file.Paths.get(configuredDir.trim()).toAbsolutePath().normalize();
    }

    public static String publicUrl(String baseUrl, String fileName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("app.uploads.public-base-url (APP_UPLOADS_PUBLIC_BASE_URL) is not configured");
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + fileName;
    }
}
