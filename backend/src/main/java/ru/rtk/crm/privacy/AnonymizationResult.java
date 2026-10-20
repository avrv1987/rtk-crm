package ru.rtk.crm.privacy;

public record AnonymizationResult(
        int contacts,
        int profiles,
        int mentions,
        int attachmentsRenamed,
        int sourceRecords,
        int technicalRecords,
        int attachmentsDeleted,
        int reportFilesDeleted,
        int learners,
        int reportFilesMarked,
        int peopleRecords
) {
    AnonymizationResult withLearners(int anonymizedLearners) {
        return new AnonymizationResult(
                contacts, profiles, mentions, attachmentsRenamed, sourceRecords, technicalRecords, attachmentsDeleted,
                reportFilesDeleted, anonymizedLearners, reportFilesMarked, peopleRecords
        );
    }

    AnonymizationResult withPeopleRecords(int anonymizedRecords) {
        return new AnonymizationResult(
                contacts, profiles, mentions, attachmentsRenamed, sourceRecords, technicalRecords, attachmentsDeleted,
                reportFilesDeleted, learners, reportFilesMarked, anonymizedRecords
        );
    }

    String summary() {
        return "контактов: " + contacts + ", профилей: " + profiles + ", анкет слушателей: " + learners
                + ", записей учёта людей и часов: " + peopleRecords
                + ", упоминаний в карточках: " + mentions + ", переименовано файлов: " + attachmentsRenamed
                + ", записей источников: " + sourceRecords + ", служебных записей: " + technicalRecords
                + ", удалено вложений: " + attachmentsDeleted + ", удалено файлов отчётов: " + reportFilesDeleted
                + ", отчётов помечено «требует пересоздания»: " + reportFilesMarked;
    }
}
