package ru.rtk.crm.report;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.reports")
public record ReportProperties(
        Path storageRoot,
        int slots,
        int queueCapacity,
        int maxRows,
        int maxPdfRows
) {
}
