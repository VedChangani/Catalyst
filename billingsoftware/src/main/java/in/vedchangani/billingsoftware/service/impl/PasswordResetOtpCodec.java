package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates one-time codes and their stored representation.
 *
 * Codes are 6 digits from a SecureRandom. What is stored is HMAC-SHA256 keyed with the dedicated
 * OTP secret over "userPublicId:code": a leaked database alone cannot be brute-forced offline
 * (the key is not in it), and a code only matches the account it was issued for. Comparison is
 * constant-time.
 */
@Component
public class PasswordResetOtpCodec {

    public static final int OTP_DIGITS = 6;
    private static final int BOUND = 1_000_000;
    private static final String HMAC = "HmacSHA256";

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public PasswordResetOtpCodec(PasswordResetProperties properties) {
        this.secret = properties.getOtpSecret().trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Exactly six digits, zero-padded. */
    public String generate() {
        return String.format("%0" + OTP_DIGITS + "d", random.nextInt(BOUND));
    }

    public String hash(String userPublicId, String otp) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return HexFormat.of().formatHex(mac.doFinal((userPublicId + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    public boolean matches(String userPublicId, String otp, String storedHash) {
        if (userPublicId == null || otp == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(userPublicId, otp).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
