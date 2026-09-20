package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.io.AnalyticsResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure date-range resolution/validation for the analytics endpoint; no Spring context. */
class AnalyticsRangeResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private static AnalyticsResponse.Range resolve(String range, LocalDate from, LocalDate to) {
        return AnalyticsServiceImpl.resolveRange(range, from, to, TODAY);
    }

    @Test
    void presets_resolveRelativeToToday() {
        AnalyticsResponse.Range today = resolve("today", null, null);
        assertEquals(TODAY, today.getFrom());
        assertEquals(TODAY, today.getTo());

        AnalyticsResponse.Range week = resolve("7d", null, null);
        assertEquals(LocalDate.of(2026, 3, 9), week.getFrom());
        assertEquals(TODAY, week.getTo());

        AnalyticsResponse.Range month = resolve("30d", null, null);
        assertEquals(LocalDate.of(2026, 2, 14), month.getFrom());
        assertEquals(TODAY, month.getTo());
    }

    @Test
    void missingOrBlankRange_defaultsTo7Days_andPresetIsCaseInsensitive() {
        assertEquals("7d", resolve(null, null, null).getPreset());
        assertEquals("7d", resolve("  ", null, null).getPreset());
        assertEquals("30d", resolve(" 30D ", null, null).getPreset());
    }

    @Test
    void custom_usesGivenDatesInclusive() {
        AnalyticsResponse.Range r = resolve("custom", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertEquals("custom", r.getPreset());
        assertEquals(LocalDate.of(2026, 1, 1), r.getFrom());
        assertEquals(LocalDate.of(2026, 1, 31), r.getTo());
        // single day is valid
        assertEquals(TODAY, resolve("custom", TODAY, TODAY).getFrom());
    }

    @Test
    void custom_validation() {
        LocalDate a = LocalDate.of(2026, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> resolve("custom", null, null));
        assertThrows(IllegalArgumentException.class, () -> resolve("custom", a, null));
        assertThrows(IllegalArgumentException.class, () -> resolve("custom", null, a));
        assertThrows(IllegalArgumentException.class, () -> resolve("custom", a.plusDays(1), a));
        // 366 days inclusive is the maximum
        resolve("custom", a, a.plusDays(365));
        assertThrows(IllegalArgumentException.class, () -> resolve("custom", a, a.plusDays(366)));
    }

    @Test
    void unknownRange_andDatesWithPreset_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> resolve("yesterday", null, null));
        assertThrows(IllegalArgumentException.class, () -> resolve("7d", TODAY, null));
        assertThrows(IllegalArgumentException.class, () -> resolve("today", null, TODAY));
    }
}
