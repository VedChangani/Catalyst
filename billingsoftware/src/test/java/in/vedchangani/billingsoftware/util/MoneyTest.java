package in.vedchangani.billingsoftware.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static in.vedchangani.billingsoftware.TestMoney.assertMoney;
import static org.junit.jupiter.api.Assertions.*;

/** The money policy in isolation: exact decimal arithmetic, one HALF_UP rounding to paise. */
class MoneyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void lineTotals_areExact_whereDoublesAreNot() {
        // the classic binary floating-point artifacts
        assertNotEquals(0.3, 0.1 * 3);
        assertNotEquals(0.3, 0.1 + 0.2);

        assertEquals(bd("0.30"), Money.lineTotal(bd("0.10"), 3));
        assertEquals(bd("0.60"), Money.lineTotal(bd("0.20"), 3));
        assertEquals(bd("32.97"), Money.lineTotal(bd("10.99"), 3));
        assertEquals(bd("59.97"), Money.lineTotal(bd("19.99"), 3));
        assertEquals(0, bd("0.30").compareTo(bd("0.10").add(bd("0.20"))));
    }

    @Test
    void tax_isOnePercent_roundedHalfUpToPaise() {
        assertMoney("2.00", Money.tax(bd("200.00")));
        assertMoney("0.10", Money.tax(bd("9.99")));      // 0.0999 -> 0.10
        assertMoney("0.32", Money.tax(bd("31.88")));     // 0.3188 -> 0.32
        assertMoney("0.01", Money.tax(bd("0.50")));      // 0.005 exactly -> HALF_UP -> 0.01
        assertMoney("0.00", Money.tax(bd("0.49")));      // 0.0049 -> 0.00
        assertEquals(2, Money.tax(bd("31.88")).scale());
    }

    @Test
    void grandTotal_isSubtotalPlusTax_withNoArtifacts() {
        BigDecimal subtotal = Money.lineTotal(bd("0.10"), 3).add(Money.lineTotal(bd("0.20"), 3))
                .add(Money.lineTotal(bd("10.99"), 1)).add(Money.lineTotal(bd("19.99"), 1));
        assertMoney("31.88", subtotal);
        BigDecimal grand = subtotal.add(Money.tax(subtotal));
        assertEquals("32.20", grand.toPlainString());
    }

    @Test
    void toMinorUnits_convertsExactly_forRazorpay() {
        assertEquals(3220L, Money.toMinorUnits(bd("32.20")));
        assertEquals(20200L, Money.toMinorUnits(bd("202.00")));
        assertEquals(30L, Money.toMinorUnits(bd("0.10").add(bd("0.20"))));
        assertEquals(1999L, Money.toMinorUnits(bd("19.99")));
        // a historical 4-decimal total (unrounded tax from before) is rounded HALF_UP to paise
        assertEquals(1110L, Money.toMinorUnits(bd("11.0999")));
        assertEquals(1110L, Money.toMinorUnits(bd("11.1000")));
        assertThrows(ArithmeticException.class, () -> Money.toMinorUnits(bd("1E+30")));
    }

    @Test
    void forResponse_showsWholePaiseWithTwoDecimals_andKeepsGenuineHistoricalPrecision() {
        assertEquals("20.20", Money.forResponse(bd("20.2000")).toPlainString());
        assertEquals("100.00", Money.forResponse(bd("100")).toPlainString());
        assertEquals("0.1099", Money.forResponse(bd("0.1099")).toPlainString());
        assertNull(Money.forResponse(null));
    }
}
