package in.vedchangani.billingsoftware.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ContactNormalizer {

    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern MOBILE_SEPARATORS = Pattern.compile("[\\s\\-().]");

    private ContactNormalizer() {
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

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
