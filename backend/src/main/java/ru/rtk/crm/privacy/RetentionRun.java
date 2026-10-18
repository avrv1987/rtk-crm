package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;

public record RetentionRun(
        OffsetDateTime appliedAt,
        int reportFilesDeleted,
        int contactsAnonymized,
        int profilesAnonymized,
        int learnersAnonymized,
        int auditEventsDeleted,
        int agentPlansExpired,
        int agentPlansDeleted
) {
    String summary() {
        return "удалено файлов отчётов: " + reportFilesDeleted + ", обезличено контактов: " + contactsAnonymized
                + ", обезличено профилей: " + profilesAnonymized + ", обезличено анкет слушателей: " + learnersAnonymized
                + ", удалено записей журнала: " + auditEventsDeleted
                + ", истекло планов ИИ-агента: " + agentPlansExpired + ", удалено планов ИИ-агента: " + agentPlansDeleted;
    }
}
