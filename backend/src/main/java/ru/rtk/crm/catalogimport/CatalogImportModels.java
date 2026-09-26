package ru.rtk.crm.catalogimport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

enum CatalogImportProfile {
    AGREEMENT,
    DIRECTION_PROGRAM,
    VENDOR_CONTACTS
}

enum CatalogImportStatus {
    PREVIEWED,
    APPLIED
}

enum CatalogImportRowStatus {
    CREATE,
    UPDATE,
    UNCHANGED,
    CONFLICT,
    INVALID
}

enum CatalogImportJobAction {
    PREVIEW,
    APPLY
}

enum CatalogImportJobStatus {
    SUCCEEDED,
    FAILED
}

record CatalogImportMapping(
        Map<String, String> columns,
        Map<Integer, CatalogImportRowTarget> rowTargets,
        Map<String, String> transferStatuses,
        UUID unassignedTeamId
) {
}

record CatalogImportRowTarget(
        UUID organizationId,
        UUID managerProfileId,
        UUID interactionId,
        UUID productAgreementId
) {
}

record CatalogImportInspectResponse(List<CatalogImportSheet> sheets) {
}

record CatalogImportSheet(String name, List<String> headers) {
}

record CatalogImportPreviewResponse(UUID jobId, UUID importId) {
}

record CatalogImportApplyRequest(Integer version, List<UUID> confirmedRowIds, List<UUID> archiveAgreementIds) {
}

record CatalogImportApplyResponse(UUID jobId, UUID importId) {
}

record CatalogImportView(
        UUID id,
        CatalogImportProfile profile,
        CatalogImportStatus status,
        int version,
        List<CatalogImportRowView> rows,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<CatalogImportMissingRecord> missingRecords
) {
}

record CatalogImportRowView(
        UUID id,
        String sheetName,
        int rowNumber,
        CatalogImportRowStatus status,
        Map<String, String> fieldErrors,
        Map<String, String> oldValues,
        Map<String, String> newValues,
        boolean applied,
        List<CatalogImportManagerCandidate> managerCandidates
) {
}

record CatalogImportManagerCandidate(UUID profileId, String displayName, String teamName, long organizationCount) {
}

record CatalogImportMissingRecord(
        UUID agreementId,
        String organizationName,
        String vendorName,
        String productName,
        String contractNumber,
        String interactionTitle
) {
}

record CatalogImportJobView(
        UUID id,
        UUID importId,
        CatalogImportJobAction action,
        CatalogImportJobStatus status,
        String result,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
}

record CatalogImportPlan(
        Map<String, String> values,
        CatalogImportRowTarget target,
        Map<String, Integer> expectedVersions,
        Map<String, String> oldValues,
        Map<String, String> newValues,
        List<UUID> managerCandidateIds
) {
}

record CatalogImportStoredRow(
        UUID id,
        String sheetName,
        int rowNumber,
        CatalogImportRowStatus status,
        CatalogImportPlan plan,
        Map<String, String> fieldErrors,
        boolean applied
) {
}

record CatalogImportStored(
        UUID id,
        UUID createdBy,
        CatalogImportProfile profile,
        CatalogImportStatus status,
        int version,
        CatalogImportMapping mapping,
        List<CatalogImportStoredRow> rows,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

record CatalogImportWorkbookSheet(String name, List<String> headers, List<CatalogImportWorkbookRow> rows) {
}

record CatalogImportWorkbookRow(int rowNumber, Map<String, String> values, Map<String, String> fieldErrors) {
}
