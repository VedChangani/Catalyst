package in.vedchangani.billingsoftware.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContactNormalizerTest {

    @Test
    void normalizeEmail_trimsAndLowerCases() {
        assertEquals("alice@example.com", ContactNormalizer.normalizeEmail("  Alice@Example.COM "));
        assertNull(ContactNormalizer.normalizeEmail(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9876543210", " 9876543210 ", "98765 43210", "98765-43210", "(98765) 43210",
            "+919876543210", "+91 98765 43210", "+91-98765-43210", "919876543210", "09876543210"
    })
    void normalizeMobile_acceptsCommonIndianFormats(String input) {
        assertEquals("9876543210", ContactNormalizer.normalizeMobile(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "12345", "1234567890", "5876543210", "98765432101", "abcdefghij", "98765abcde",
            "+1 9876543210", "+4498765432100", "0098765432"
    })
    void normalizeMobile_rejectsInvalidNumbers(String input) {
        assertNull(ContactNormalizer.normalizeMobile(input));
    }
}
