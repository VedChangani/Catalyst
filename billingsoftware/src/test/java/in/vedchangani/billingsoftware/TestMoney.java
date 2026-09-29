package in.vedchangani.billingsoftware;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TestMoney {

    private TestMoney() {
    }

    public static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    public static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but was null");
        assertTrue(new BigDecimal(expected).compareTo(actual) == 0, "expected " + expected + " but was " + actual);
    }
}
