package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

enum SourceCode {
    WEBSITE("Сайт ИТ Школы (Laravel)"),
    MOODLE("LMS Moodle");

    private final String title;

    SourceCode(String title) {
        this.title = title;
    }

    String title() {
        return title;
    }
}

enum SyncRunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}

enum SourceRecordStatus {
    APPLIED,
    NEEDS_MAPPING,
    FAILED,
    SKIPPED
}

enum SyncOutcome {
    CREATED,
    UPDATED,
    SKIPPED,
    NEEDS_MAPPING,
    FAILED
}

record SyncRunView(
        UUID id,
        SourceCode source,
        SyncRunStatus status,
        OffsetDateTime updatedSince,
        int fetchedCount,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int needsMappingCount,
        int failedCount,
        String errorCode,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
}

record SourceView(
        SourceCode source,
        String title,
        boolean adapterAvailable,
        boolean configured,
        boolean proposedContract,
        OffsetDateTime updatedSince,
        OffsetDateTime lastSuccessAt,
        long problemCount,
        SyncRunView lastRun
) {
}

record SourceRecordView(
        UUID id,
        SourceCode source,
        String recordType,
        String externalId,
        OffsetDateTime externalUpdatedAt,
        String externalStatus,
        SourceRecordStatus status,
        String error,
        String organizationExternalId,
        String organizationName,
        String programName,
        UUID organizationId,
        UUID programId,
        UUID interactionId,
        OffsetDateTime updatedAt
) {
}

record SourceRecordApplyRequest(UUID organizationId, UUID programId, LocalDate runStartsOn, LocalDate runEndsOn) {
}

record SourceRecordApplyResult(SourceRecordView record, int reappliedCount) {
}

record SyncRunCreated(UUID runId) {
}

record SourceMappingOption(UUID id, String name) {
}

record SourceMappingOptions(List<SourceMappingOption> organizations, List<SourceMappingOption> programs) {
}
