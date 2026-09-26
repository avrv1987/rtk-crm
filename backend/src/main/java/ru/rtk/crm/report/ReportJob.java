package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ReportJob(
        UUID id,
        ReportKind kind,
        ReportFormat format,
        StatisticsGroupBy groupBy,
        ChartType chartType,
        StatisticsGroupBy seriesBy,
        ReportJobStatus status,
        int progress,
        boolean resultReady,
        Integer rowCount,
        String fileName,
        @JsonInclude(JsonInclude.Include.NON_NULL) Error error,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
    public record Error(String code, String message) {
    }
}
