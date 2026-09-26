package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;

public record RetentionRun(
        OffsetDateTime appliedAt,
        int reportFilesDeleted,
        int contactsAnonymized,
        int profilesAnonymized,
        int learnersAnonymized,
        int auditEventsDeleted
) {
    String summary() {
        return "удалено файлов отчётов: " + reportFilesDeleted + ", обезличено контактов: " + contactsAnonymized
                + ", обезличено профилей: " + profilesAnonymized + ", обезличено анкет слушателей: " + learnersAnonymized
                + ", удалено записей журнала: " + auditEventsDeleted;
    }
}
