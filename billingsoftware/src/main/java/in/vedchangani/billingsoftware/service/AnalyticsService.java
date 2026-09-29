package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.io.AnalyticsResponse;

import java.time.LocalDate;

public interface AnalyticsService {

    AnalyticsResponse getAnalytics(String range, LocalDate from, LocalDate to);
}