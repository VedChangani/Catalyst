package in.vedchangani.billingsoftware.util;

import java.util.regex.Pattern;

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
