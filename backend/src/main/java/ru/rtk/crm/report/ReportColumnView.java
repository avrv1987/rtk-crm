package ru.rtk.crm.report;

public record ReportColumnView(ReportColumn id, String title, String emptyText) {
    static ReportColumnView of(ReportColumn column, ReportKind kind) {
        return new ReportColumnView(column, column.title(kind), column.emptyText());
    }
}
