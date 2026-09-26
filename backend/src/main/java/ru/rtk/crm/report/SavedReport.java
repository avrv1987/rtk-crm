package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SavedReport(
        UUID id,
        String name,
        ReportRequest definition,
        SavedReportPeriod period,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
