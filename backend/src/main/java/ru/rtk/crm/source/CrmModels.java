package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

enum CrmEventKind {
    STAGE_CHANGED,
    NEXT_STEP_CHANGED,
    MANAGER_CHANGED,
    AGREEMENT_STATUS
}

enum CrmEventStatus {
    QUEUED,
    SENT,
    ERROR
}

enum CrmPreviewOutcome {
    FOUND,
    NOT_FOUND,
    CREATE,
    UPDATE,
    AMBIGUOUS,
    ORGANIZATION_UNRESOLVED,
    UNCHANGED,
    RESTRICTED,
    INVALID
}

record CrmEventView(
        UUID id,
        CrmEventKind kind,
        CrmEventStatus status,
        OffsetDateTime occurredAt,
        String organizationName,
        String workTitle,
        String description,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError,
        OffsetDateTime sentAt
) {
}

record CrmEventCounts(long queued, long sent, long error) {
}

record CrmEventList(CrmEventCounts counts, List<CrmEventView> items) {
}

record CrmEventsRequeued(int requeued) {
}

record CrmPreviewItem(String recordType, String externalId, String name, CrmPreviewOutcome outcome, String note) {
}

record CrmPreviewCount(CrmPreviewOutcome outcome, int count) {
}

record CrmPreview(
        OffsetDateTime updatedSince,
        int organizations,
        int contacts,
        List<CrmPreviewCount> counts,
        List<CrmPreviewItem> items,
        boolean truncated
) {
}
