package in.vedchangani.billingsoftware.service;

/**
 * Customer-only forgot-password with an emailed one-time code. Anonymous: ownership is proven by
 * the email address plus the code sent to it - never by a user id, name or phone number.
 * Only enabled ROLE_USER accounts take part; cashier/admin recovery is untouched.
 */
public interface PasswordResetService {

    /**
     * Issues and emails a code when the email belongs to an enabled customer account. For anything
     * else (unknown email, cashier, admin, disabled account) - and when the account's cooldown or
     * hourly cap applies - it does nothing and returns normally, so callers can answer every
     * request identically.
     */
    void requestReset(String email);

    /**
     * Sets a new password when the code is valid. Every problem with the code or the account
     * (unknown/ineligible email, no code, expired, used, too many attempts, wrong code) throws the
     * same IllegalArgumentException(INVALID_CODE_MESSAGE); a confirmation mismatch or a password
     * that breaks the policy is reported as such and does not use up an attempt. On success the
     * password is replaced and every existing session is revoked (tokenVersion); no token is issued.
     */
    void resetPassword(String email, String otp, String newPassword, String confirmNewPassword);

    String INVALID_CODE_MESSAGE = "The code is invalid or has expired.";
}
