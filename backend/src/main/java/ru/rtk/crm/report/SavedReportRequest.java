package ru.rtk.crm.report;

public record SavedReportRequest(String name, ReportRequest definition, Integer version, SavedReportPeriod period) {
}
