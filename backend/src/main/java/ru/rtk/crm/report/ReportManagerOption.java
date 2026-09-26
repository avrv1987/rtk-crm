package ru.rtk.crm.report;

import java.util.UUID;

public record ReportManagerOption(UUID id, String displayName, boolean active) {
}
