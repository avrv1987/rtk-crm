package ru.rtk.crm.audit;

import java.util.List;

public record AuditEntryPage(List<AuditEntry> items, int page, int size, long total) {
}
