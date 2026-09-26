package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.enrolment.LearnerIntake;

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

enum RunKind {
    STUDENTS,
    TEACHERS
}

enum SyncTrigger {
    MANUAL,
    SCHEDULE,
    CARD,
    BOOTSTRAP,
    UPLOAD
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
        OffsetDateTime finishedAt,
        SyncTrigger trigger,
        String startedByName,
        String organizationName
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
        SyncRunView lastRun,
        String schedule,
        OffsetDateTime nextRunAt,
        boolean stale
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
        Integer streamNo,
        UUID organizationId,
        UUID programId,
        UUID interactionId,
        OffsetDateTime updatedAt
) {
}

record SourceRecordApplyRequest(
        UUID organizationId,
        UUID programId,
        LocalDate runStartsOn,
        LocalDate runEndsOn,
        RunKind runKind
) {
    SourceRecordApplyRequest(UUID organizationId, UUID programId, LocalDate runStartsOn, LocalDate runEndsOn) {
        this(organizationId, programId, runStartsOn, runEndsOn, null);
    }
}

record SourceRecordApplyResult(SourceRecordView record, int reappliedCount) {
}

record SyncRunCreated(UUID runId) {
}

record SourceMappingOption(UUID id, String name) {
}

record SourceMappingOptions(List<SourceMappingOption> organizations, List<SourceMappingOption> programs) {
}

record SourceMappingView(
        UUID id,
        SourceCode source,
        String kind,
        String externalKey,
        String label,
        UUID organizationId,
        String organizationName,
        UUID programId,
        String programName,
        LocalDate runStartsOn,
        LocalDate runEndsOn,
        RunKind runKind,
        int version,
        String updatedByName,
        OffsetDateTime updatedAt,
        Integer participants,
        OffsetDateTime observedAt,
        boolean closed,
        boolean outdated
) {
}

record SourceMappingRequest(
        Integer version,
        UUID organizationId,
        UUID programId,
        LocalDate runStartsOn,
        LocalDate runEndsOn,
        RunKind runKind
) {
}

record PendingSourceRecordView(
        UUID id,
        String recordType,
        String externalId,
        OffsetDateTime submittedAt,
        SourceRecordStatus status,
        String error,
        String organizationName,
        String organizationExternalId,
        String programName,
        UUID organizationId,
        String crmOrganizationName,
        boolean canResolve
) {
}

record SourceRecordResolveRequest(UUID organizationId) {
}

record SourceOrganizationCreateRequest(String name, String type, UUID teamId) {
}

record SourceOrganizationCreated(UUID organizationId, String organizationName, SourceRecordApplyResult result) {
}

record PaidOrderApplied(SyncOutcome outcome, LearnerIntake intake) {
}
