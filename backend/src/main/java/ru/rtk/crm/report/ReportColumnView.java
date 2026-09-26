package ru.rtk.crm.report;

public record ReportColumnView(ReportColumn id, String title, String emptyText) {
    static ReportColumnView of(ReportColumn column, String title) {
        return new ReportColumnView(column, title, column.emptyText());
    }
}
