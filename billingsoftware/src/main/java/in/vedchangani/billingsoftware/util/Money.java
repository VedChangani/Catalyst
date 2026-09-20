package in.vedchangani.billingsoftware.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single money policy for billing (INR). All monetary values are BigDecimal - never
 * float/double - so no binary floating-point artifact can enter a price or total.
 *
 *   line total  = unit price x quantity                  (exact; catalog prices have <= 2 decimals)
 *   subtotal    = sum of line totals                     (exact)
 *   tax         = subtotal x 1%, rounded HALF_UP to 2 decimals (paise) - the one rounding step
 *   grand total = subtotal + tax                         (exact, therefore also 2 decimals)
 *
 * The grand total is what is charged; Razorpay receives it in paise via toMinorUnits.
 */
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

    // Amount in the smallest currency unit (paise), e.g. 11.10 -> 1110. Rounded to paise first
    // (HALF_UP), then converted exactly - an amount that does not fit a long is an error, never
    // silently truncated.
    public static long toMinorUnits(BigDecimal amount) {
        return atCurrencyScale(amount).movePointRight(SCALE).longValueExact();
    }

    // For API responses: values that are whole paise are shown with exactly 2 decimals
    // (20.2000 read from a DECIMAL(19,4) column -> 20.20); a historical value with genuine
    // sub-paise precision (tax recorded before rounding existed) is returned unchanged.
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
