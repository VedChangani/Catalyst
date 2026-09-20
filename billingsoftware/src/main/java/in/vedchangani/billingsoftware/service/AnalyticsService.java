package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.AnalyticsResponse;

import java.time.LocalDate;

public interface AnalyticsService {

    // range: today | 7d | 30d | custom (null = 7d). from/to are only valid (and both required)
    // with custom. Invalid input -> IllegalArgumentException (HTTP 400).
    AnalyticsResponse getAnalytics(String range, LocalDate from, LocalDate to);
}