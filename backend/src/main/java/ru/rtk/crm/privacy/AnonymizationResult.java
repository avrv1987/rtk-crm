package ru.rtk.crm.privacy;

public record AnonymizationResult(
        int contacts,
        int profiles,
        int mentions,
        int sourceRecords,
        int technicalRecords,
        int attachmentsDeleted,
        int reportFilesDeleted,
        int learners
) {
    boolean changedAnything() {
        return contacts + profiles + mentions + sourceRecords + technicalRecords + attachmentsDeleted > 0;
    }

    AnonymizationResult withLearners(int anonymizedLearners) {
        return new AnonymizationResult(
                contacts, profiles, mentions, sourceRecords, technicalRecords, attachmentsDeleted, reportFilesDeleted,
                anonymizedLearners
        );
    }

    String summary() {
        return "контактов: " + contacts + ", профилей: " + profiles + ", анкет слушателей: " + learners
                + ", упоминаний в карточках: " + mentions + ", записей источников: " + sourceRecords
                + ", служебных записей: " + technicalRecords + ", удалено вложений: " + attachmentsDeleted
                + ", удалено файлов отчётов: " + reportFilesDeleted;
    }
}
