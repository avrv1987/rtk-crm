package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ReportPreview(
        ReportKind kind,
        OffsetDateTime generatedAt,
        List<String> notes,
        List<ReportColumnView> columns,
        List<Map<String, Object>> items,
        int page,
        int size,
        long total
) {
}
