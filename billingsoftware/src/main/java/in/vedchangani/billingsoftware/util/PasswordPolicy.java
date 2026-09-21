package in.vedchangani.billingsoftware.util;

import java.util.regex.Pattern;

/**
 * The single, authoritative password policy: 8-72 characters with at least one lowercase letter,
 * one uppercase letter, one digit and one special (non-alphanumeric) character. 72 is BCrypt's
 * input limit - anything longer would be silently truncated. Used through the {@code @StrongPassword}
 * constraint on request DTOs and directly by services; never re-declare these rules elsewhere.
 *
 * It is only applied when a password is SET. Existing hashes are never inspected, so passwords
 * created under the older (letter + number) rule keep working until their owner changes them.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;
    public static final String MESSAGE = "Password must be 8-72 characters and include an uppercase letter, "
            + "a lowercase letter, a number and a special character";

    private static final Pattern POLICY = Pattern.compile(
            "^(?=.*\\p{Lower})(?=.*\\p{Upper})(?=.*\\d)(?=.*[^\\p{Alnum}]).{" + MIN_LENGTH + "," + MAX_LENGTH + "}$",
            Pattern.DOTALL);

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return password != null && POLICY.matcher(password).matches();
    }
}
