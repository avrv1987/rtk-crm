package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record StatisticsResult(
        ReportKind kind,
        StatisticsGroupBy groupBy,
        String unit,
        LocalDate from,
        LocalDate to,
        PeriodBasis periodBasis,
        String timezone,
        OffsetDateTime generatedAt,
        ReportFilters filters,
        List<String> notes,
        long total,
        long unknownCount,
        List<Item> items,
        int maxChartBars
) {
    public static final int MAX_CHART_BARS = 100;

    public record Item(String key, String label, long count) {
    }
}
