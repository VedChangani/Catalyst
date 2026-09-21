package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PasswordResetOtpCodecTest {

    private static PasswordResetOtpCodec codec(String secret) {
        PasswordResetProperties p = new PasswordResetProperties();
        p.setOtpSecret(secret);
        return new PasswordResetOtpCodec(p);
    }

    private final PasswordResetOtpCodec codec = codec("codec-test-secret-0123456789-0123456789");

    @Test
    void generatedCodesAreAlwaysExactlySixDigitsIncludingLeadingZeros() {
        Set<String> distinct = new HashSet<>();
        boolean sawLeadingZero = false;
        for (int i = 0; i < 20_000; i++) {
            String otp = codec.generate();
            assertTrue(otp.matches("\\d{6}"), otp);
            sawLeadingZero |= otp.startsWith("0");
            distinct.add(otp);
        }
        assertTrue(sawLeadingZero, "zero-padding must be exercised");
        assertTrue(distinct.size() > 15_000, "codes should look random");
    }

    @Test
    void theStoredRepresentationIsAKeyedHmacNotThePlaintext() {
        String hash = codec.hash("user-1", "123456");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertFalse(hash.contains("123456"));
        assertEquals(hash, codec.hash("user-1", "123456"), "deterministic for the same inputs");
        // a different secret gives a different value: a leaked table alone cannot be verified offline
        assertNotEquals(hash, codec("another-secret-0123456789-0123456789-xx").hash("user-1", "123456"));
    }

    @Test
    void matchesOnlyTheRightCodeForTheRightUser() {
        String hash = codec.hash("user-1", "123456");
        assertTrue(codec.matches("user-1", "123456", hash));
        assertFalse(codec.matches("user-1", "123457", hash), "wrong code");
        assertFalse(codec.matches("user-2", "123456", hash), "same code, different user");
        assertFalse(codec.matches("user-1", null, hash));
        assertFalse(codec.matches("user-1", "123456", null));
        assertNotEquals(codec.hash("user-2", "123456"), hash);
    }
}
