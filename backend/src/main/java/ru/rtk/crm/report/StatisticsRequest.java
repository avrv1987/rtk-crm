package ru.rtk.crm.report;

import java.time.LocalDate;

public record StatisticsRequest(
        ReportKind kind,
        StatisticsGroupBy groupBy,
        LocalDate from,
        LocalDate to,
        PeriodBasis periodBasis,
        ReportFilters filters,
        LocalDate asOf,
        StatisticsGroupBy seriesBy
) {
    ReportRequest toReportRequest() {
        return new ReportRequest(kind, from, to, periodBasis, filters, null, null, groupBy, null, asOf, null, seriesBy);
    }
}
