package in.vedchangani.billingsoftware.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal TAX_RATE = new BigDecimal("0.01");

    private Money() {
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public static BigDecimal tax(BigDecimal subtotal) {
        return subtotal.multiply(TAX_RATE).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal atCurrencyScale(BigDecimal amount) {
        return amount.setScale(SCALE, ROUNDING);
    }

    public static long toMinorUnits(BigDecimal amount) {
        return atCurrencyScale(amount).movePointRight(SCALE).longValueExact();
    }

    public static BigDecimal forResponse(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() <= SCALE ? amount.setScale(SCALE, ROUNDING) : stripped;
    }

    public static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
