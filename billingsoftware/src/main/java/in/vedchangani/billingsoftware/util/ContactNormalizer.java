package in.vedchangani.billingsoftware.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical forms of the account identifiers used for registration and login. Every write and
 * every lookup goes through these methods, so "Alice@Example.com " and "alice@example.com" (or
 * "+91 98765-43210" and "9876543210") always resolve to the same stored value.
 */
public final class ContactNormalizer {

    // Indian mobile numbers: 10 digits starting with 6-9.
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    // Separators people commonly type inside a phone number.
    private static final Pattern MOBILE_SEPARATORS = Pattern.compile("[\\s\\-().]");

    private ContactNormalizer() {
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the 10-digit canonical mobile number, or null when the input is not a valid Indian
     * mobile number. Accepts an optional +91 / 91 country code or a leading trunk 0.
     */
    public static String normalizeMobile(String mobile) {
        if (mobile == null) {
            return null;
        }
        String digits = MOBILE_SEPARATORS.matcher(mobile.trim()).replaceAll("");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return INDIAN_MOBILE.matcher(digits).matches() ? digits : null;
    }
}
