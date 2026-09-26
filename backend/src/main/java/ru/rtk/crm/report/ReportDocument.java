package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReportDocument(
        ReportRequest request,
        OffsetDateTime generatedAt,
        List<String> notes,
        List<ReportRow> rows,
        Map<ReportColumn, String> columnTitles
) {
    public ReportDocument(ReportRequest request, OffsetDateTime generatedAt, List<String> notes, List<ReportRow> rows) {
        this(request, generatedAt, notes, rows, Map.of());
    }

    public String columnTitle(ReportColumn column) {
        return columnTitles.getOrDefault(column, column.title(request.kind()));
    }

    public String title() {
        return request.kind().title();
    }

    public List<ReportColumn> columns() {
        return request.columns();
    }

    public long organizationCount() {
        return rows.stream().map(ReportRow::organizationId).distinct().count();
    }

    public long interactionCount() {
        return rows.stream().map(ReportRow::interactionId).filter(Objects::nonNull).distinct().count();
    }

    public long countWithout(ReportColumn column) {
        return rows.stream().map(column::value).filter(Objects::isNull).count();
    }
}
