package in.vedchangani.billingsoftware.util;

public final class UploadUrls {

    private UploadUrls() {
    }

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
