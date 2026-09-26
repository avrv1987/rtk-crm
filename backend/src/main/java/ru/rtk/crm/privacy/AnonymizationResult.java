package ru.rtk.crm.privacy;

public record AnonymizationResult(
        int contacts,
        int profiles,
        int mentions,
        int sourceRecords,
        int technicalRecords,
        int attachmentsDeleted,
        int reportFilesDeleted
) {
    boolean changedAnything() {
        return contacts + profiles + mentions + sourceRecords + technicalRecords + attachmentsDeleted > 0;
    }

    String summary() {
        return "контактов: " + contacts + ", профилей: " + profiles + ", упоминаний в карточках: " + mentions
                + ", записей источников: " + sourceRecords + ", служебных записей: " + technicalRecords
                + ", удалено вложений: " + attachmentsDeleted + ", удалено файлов отчётов: " + reportFilesDeleted;
    }
}
