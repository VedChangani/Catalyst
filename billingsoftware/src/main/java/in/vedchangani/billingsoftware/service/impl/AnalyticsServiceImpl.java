package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.io.AnalyticsResponse;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.PaymentMethod;
import in.vedchangani.billingsoftware.io.SalesChannel;
import in.vedchangani.billingsoftware.repository.AnalyticsRepository;
import in.vedchangani.billingsoftware.service.AnalyticsService;
import in.vedchangani.billingsoftware.util.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    static final int MAX_RANGE_DAYS = 366;
    private static final String UNKNOWN = "UNKNOWN";
    // The database returns only the top rows; nothing is sorted or trimmed in Java.
    private static final Pageable TOP_PRODUCTS = PageRequest.of(0, 5);

    private final AnalyticsRepository analyticsRepository;

    @Override
    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(String range, LocalDate from, LocalDate to) {
        AnalyticsResponse.Range resolved = resolveRange(range, from, to, LocalDate.now());
        // Half-open [from 00:00, to+1 00:00): the whole of the last day is included.
        LocalDateTime start = resolved.getFrom().atStartOfDay();
        LocalDateTime end = resolved.getTo().plusDays(1).atStartOfDay();

        AnalyticsRepository.Totals totals = analyticsRepository.paidTotals(start, end);
        BigDecimal revenue = Money.zeroIfNull(totals.getRevenue());
        long paidOrders = nz(totals.getOrderCount());

        return AnalyticsResponse.builder()
                .range(resolved)
                .kpis(AnalyticsResponse.Kpis.builder()
                        .revenue(money(revenue))
                        .paidOrders(paidOrders)
                        .averageOrderValue(paidOrders == 0 ? money(BigDecimal.ZERO)
                                : revenue.divide(BigDecimal.valueOf(paidOrders), Money.SCALE, Money.ROUNDING))
                        .build())
                .daily(dailySeries(resolved, start, end))
                .channels(channelBreakdown(start, end, revenue, paidOrders))
                .paymentMethods(paymentMethodBreakdown(start, end, revenue, paidOrders))
                .orderStatusCounts(statusCounts(analyticsRepository.countByStatus(start, end)))
                .upiSuccessRate(upiSuccessRate(statusCounts(analyticsRepository.countUpiByStatus(start, end))))
                .topByQuantity(toTopProducts(analyticsRepository.topProductsByQuantity(start, end, TOP_PRODUCTS)))
                .topByRevenue(toTopProducts(analyticsRepository.topProductsByRevenue(start, end, TOP_PRODUCTS)))
                .inventory(inventorySummary())
                .build();
    }

    // today = [today, today]; 7d = today-6..today; 30d = today-29..today; custom = from..to.
    // Package-private and takes "today" so the presets are testable without a clock.
    static AnalyticsResponse.Range resolveRange(String range, LocalDate from, LocalDate to, LocalDate today) {
        String preset = (range == null || range.isBlank()) ? "7d" : range.trim().toLowerCase(Locale.ROOT);
        LocalDate start;
        LocalDate end;
        switch (preset) {
            case "today" -> { start = today; end = today; }
            case "7d" -> { start = today.minusDays(6); end = today; }
            case "30d" -> { start = today.minusDays(29); end = today; }
            case "custom" -> {
                if (from == null || to == null) {
                    throw new IllegalArgumentException("range=custom requires both 'from' and 'to'");
                }
                if (from.isAfter(to)) {
                    throw new IllegalArgumentException("'from' must not be after 'to'");
                }
                if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                    throw new IllegalArgumentException("Date range must not exceed " + MAX_RANGE_DAYS + " days");
                }
                start = from;
                end = to;
            }
            default -> throw new IllegalArgumentException("range must be one of today, 7d, 30d, custom");
        }
        if (!preset.equals("custom") && (from != null || to != null)) {
            throw new IllegalArgumentException("'from' and 'to' are only allowed with range=custom");
        }
        return AnalyticsResponse.Range.builder().preset(preset).from(start).to(end).build();
    }

    // One point per calendar day of the range, so days without sales appear as zero.
    private List<AnalyticsResponse.DailyPoint> dailySeries(AnalyticsResponse.Range range,
                                                          LocalDateTime start, LocalDateTime end) {
        Map<LocalDate, AnalyticsResponse.DailyPoint> byDay = new LinkedHashMap<>();
        for (LocalDate d = range.getFrom(); !d.isAfter(range.getTo()); d = d.plusDays(1)) {
            byDay.put(d, AnalyticsResponse.DailyPoint.builder().date(d).revenue(money(BigDecimal.ZERO)).orders(0L).build());
        }
        for (AnalyticsRepository.DailyRow row : analyticsRepository.paidByDay(start, end)) {
            byDay.put(row.getDay(), AnalyticsResponse.DailyPoint.builder()
                    .date(row.getDay()).revenue(money(Money.zeroIfNull(row.getRevenue()))).orders(nz(row.getOrderCount())).build());
        }
        return new ArrayList<>(byDay.values());
    }

    private List<AnalyticsResponse.ChannelBreakdown> channelBreakdown(LocalDateTime start, LocalDateTime end,
                                                                     BigDecimal totalRevenue, long totalOrders) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (SalesChannel c : SalesChannel.values()) {
            buckets.put(c.name(), new Bucket());
        }
        buckets.put(UNKNOWN, new Bucket());
        for (AnalyticsRepository.ChannelRow row : analyticsRepository.paidByChannel(start, end)) {
            add(buckets, row.getChannel() == null ? UNKNOWN : row.getChannel().name(), row.getRevenue(), row.getOrderCount());
        }
        List<AnalyticsResponse.ChannelBreakdown> result = new ArrayList<>();
        buckets.forEach((name, v) -> result.add(AnalyticsResponse.ChannelBreakdown.builder()
                .channel(name).revenue(money(v.revenue)).orders(v.orders)
                .revenueShare(share(v.revenue, totalRevenue)).orderShare(share(v.orders, totalOrders)).build()));
        return result;
    }

    private List<AnalyticsResponse.PaymentMethodBreakdown> paymentMethodBreakdown(LocalDateTime start, LocalDateTime end,
                                                                                 BigDecimal totalRevenue, long totalOrders) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (PaymentMethod m : PaymentMethod.values()) {
            buckets.put(m.name(), new Bucket());
        }
        buckets.put(UNKNOWN, new Bucket());
        for (AnalyticsRepository.MethodRow row : analyticsRepository.paidByPaymentMethod(start, end)) {
            add(buckets, row.getMethod() == null ? UNKNOWN : row.getMethod().name(), row.getRevenue(), row.getOrderCount());
        }
        List<AnalyticsResponse.PaymentMethodBreakdown> result = new ArrayList<>();
        buckets.forEach((name, v) -> result.add(AnalyticsResponse.PaymentMethodBreakdown.builder()
                .method(name).revenue(money(v.revenue)).orders(v.orders)
                .revenueShare(share(v.revenue, totalRevenue)).orderShare(share(v.orders, totalOrders)).build()));
        return result;
    }

    // Revenue (BigDecimal) and paid-order count accumulated per channel / payment method.
    private static final class Bucket {
        private BigDecimal revenue = BigDecimal.ZERO;
        private long orders;
    }

    private static void add(Map<String, Bucket> buckets, String key, BigDecimal revenue, Long orders) {
        Bucket bucket = buckets.get(key);
        bucket.revenue = bucket.revenue.add(Money.zeroIfNull(revenue));
        bucket.orders += nz(orders);
    }

    private static List<AnalyticsResponse.TopProduct> toTopProducts(List<AnalyticsRepository.TopProductRow> rows) {
        List<AnalyticsResponse.TopProduct> result = new ArrayList<>();
        for (AnalyticsRepository.TopProductRow row : rows) {
            result.add(AnalyticsResponse.TopProduct.builder()
                    .itemId(row.getItemId()).name(row.getName())
                    .quantity(nz(row.getQuantity())).revenue(money(Money.zeroIfNull(row.getRevenue()))).build());
        }
        return result;
    }

    private AnalyticsResponse.InventorySummary inventorySummary() {
        AnalyticsRepository.InventoryRow row = analyticsRepository.inventorySummary();
        return AnalyticsResponse.InventorySummary.builder()
                .lowStock(nz(row.getLowStock())).outOfStock(nz(row.getOutOfStock()))
                .untracked(nz(row.getUntracked())).build();
    }

    // All four statuses, zero-filled. Rows with a NULL status never reach here (excluded by the query).
    private static Map<OrderStatus, Long> statusCounts(List<AnalyticsRepository.StatusRow> rows) {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            counts.put(s, 0L);
        }
        for (AnalyticsRepository.StatusRow row : rows) {
            if (row.getStatus() != null) {
                counts.merge(row.getStatus(), nz(row.getOrderCount()), Long::sum);
            }
        }
        return counts;
    }

    private static Double upiSuccessRate(Map<OrderStatus, Long> upiCounts) {
        long paid = upiCounts.get(OrderStatus.PAID);
        long settled = paid + upiCounts.get(OrderStatus.PAYMENT_FAILED) + upiCounts.get(OrderStatus.CANCELLED);
        return settled == 0 ? null : round4((double) paid / settled);
    }

    // Shares are ratios (0..1, 4 decimals), not money, so they stay Double; the revenue division
    // itself is decimal.
    private static double share(BigDecimal part, BigDecimal total) {
        return total.signum() <= 0 ? 0.0 : part.divide(total, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static double share(long part, long total) {
        return total <= 0 ? 0.0 : round4((double) part / total);
    }

    // Money in responses: 2 decimals (sums of whole-paise totals are already exact at that scale).
    private static BigDecimal money(BigDecimal amount) {
        return Money.atCurrencyScale(amount);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}