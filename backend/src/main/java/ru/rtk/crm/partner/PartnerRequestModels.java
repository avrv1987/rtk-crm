package ru.rtk.crm.partner;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.attachment.AttachmentStatus;

public final class PartnerRequestModels {
    private PartnerRequestModels() {
    }

    public enum PartnerRequestStatus {
        AWAITING_RESPONSE,
        CLARIFICATION_NEEDED,
        RESPONDED,
        ACCEPTED,
        RETURNED,
        CANCELLED
    }

    public enum PartnerResponseKind {
        ANSWER,
        QUESTION
    }

    public enum PartnerResponseDecision {
        ACCEPTED,
        RETURNED
    }

    public enum PartnerFileState {
        CHECKING,
        CLEAN,
        REJECTED
    }

    public record PartnerRequestCreate(UUID contactId, String action, String expectedResult, LocalDate dueOn, List<UUID> materialIds) {
    }

    public record PartnerRequestReview(Integer version, PartnerResponseDecision decision, String reason) {
    }

    public record PartnerRequestClarify(Integer version, String text) {
    }

    public record PartnerRequestCancel(Integer version, String reason) {
    }

    public record PartnerRequestMaterial(
            UUID id,
            String name,
            int revision,
            boolean downloadable,
            OffsetDateTime receivedAt
    ) {
    }

    public record PartnerRequestResponse(
            UUID id,
            String receiptNumber,
            PartnerResponseKind kind,
            String message,
            UUID attachmentId,
            String fileName,
            PartnerFileState fileState,
            OffsetDateTime submittedAt,
            String submittedByName,
            PartnerResponseDecision decision,
            String decisionReason,
            OffsetDateTime decidedAt
    ) {
    }

    // Представление для сотрудников школы: адресат, автор, ответы с файлами для скачивания через обычные вложения.
    public record PartnerRequestView(
            UUID id,
            UUID interactionId,
            UUID contactId,
            String contactName,
            String action,
            String expectedResult,
            LocalDate dueOn,
            PartnerRequestStatus status,
            String returnReason,
            String clarification,
            String cancelReason,
            List<PartnerRequestMaterial> materials,
            List<PartnerRequestResponse> responses,
            String createdByName,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    // Представление для координатора вуза: без автора, внутренних идентификаторов файлов и сведений о других запросах.
    public record PartnerRequestForPartner(
            UUID id,
            UUID workId,
            String workTitle,
            String action,
            String expectedResult,
            LocalDate dueOn,
            PartnerRequestStatus status,
            String returnReason,
            String clarification,
            List<PartnerRequestMaterial> materials,
            List<PartnerReceipt> responses,
            OffsetDateTime createdAt,
            int version
    ) {
    }

    // responseId и downloadable — для скачивания своего файла ответа; идентификатор файла наружу не отдаётся.
    public record PartnerReceipt(
            UUID responseId,
            String receiptNumber,
            PartnerResponseKind kind,
            String message,
            String fileName,
            PartnerFileState fileState,
            OffsetDateTime submittedAt,
            PartnerResponseDecision decision,
            String decisionReason,
            boolean downloadable
    ) {
    }

    public record PartnerReviewItem(
            UUID requestId,
            UUID interactionId,
            UUID organizationId,
            String organizationName,
            String workTitle,
            String action,
            PartnerRequestStatus status,
            OffsetDateTime updatedAt
    ) {
    }

    public record PartnerMaterialReceipt(UUID documentId, OffsetDateTime receivedAt) {
    }

    static PartnerFileState fileState(AttachmentStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case CLEAN -> PartnerFileState.CLEAN;
            case REJECTED -> PartnerFileState.REJECTED;
            case QUARANTINE, UNVERIFIABLE -> PartnerFileState.CHECKING;
        };
    }
}
