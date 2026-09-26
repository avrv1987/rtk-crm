package ru.rtk.crm.agreement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AgreementModels {
    private AgreementModels() {
    }

    public enum AgreementStatus {
        DRAFT("Проект"),
        ACTIVE("Действует"),
        COMPLETED("Завершено"),
        TERMINATED("Расторгнуто");

        private final String title;

        AgreementStatus(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public enum ActivityStatus {
        PLANNED("Запланировано"),
        IN_PROGRESS("Выполняется"),
        DONE("Выполнено"),
        CANCELLED("Отменено");

        private final String title;

        ActivityStatus(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public record ActivityKind(UUID id, String name, int sortOrder, boolean archived, int version) {
    }

    public record ActivityKindCreateRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record ActivityKindUpdateRequest(
            @NotNull @Min(0) Integer version,
            @NotBlank @Size(max = 200) String name,
            @NotNull Boolean archived
    ) {
    }

    public record AgreementSummary(
            UUID id,
            UUID organizationId,
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            AgreementStatus status,
            int activityCount,
            int confirmationCount,
            int version
    ) {
    }

    public record Agreement(
            UUID id,
            UUID organizationId,
            String organizationName,
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            String parties,
            AgreementStatus status,
            LinkedAttachment file,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<Activity> activities
    ) {
    }

    public record AgreementRequest(
            @Min(0) Integer version,
            @NotBlank @Size(max = 100) String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            @Size(max = 2000) String parties,
            @NotNull AgreementStatus status,
            UUID fileAttachmentId
    ) {
    }

    public record Activity(
            UUID id,
            UUID agreementId,
            UUID kindId,
            String kindName,
            String title,
            String unit,
            Integer plannedVolume,
            Integer actualVolume,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate actualStart,
            LocalDate actualEnd,
            UUID responsibleProfileId,
            String responsibleName,
            ActivityStatus status,
            int version,
            List<LinkedInteraction> interactions,
            List<LinkedAttachment> attachments
    ) {
    }

    public record ActivityRequest(
            @Min(0) Integer version,
            @NotNull UUID kindId,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 50) String unit,
            @Min(0) Integer plannedVolume,
            @Min(0) Integer actualVolume,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate actualStart,
            LocalDate actualEnd,
            UUID responsibleProfileId,
            @NotNull ActivityStatus status,
            @Size(max = 100) List<UUID> interactionIds,
            @Size(max = 100) List<UUID> attachmentIds
    ) {
    }

    public record LinkedInteraction(UUID id, String title) {
    }

    public record LinkedAttachment(
            UUID id,
            String originalName,
            UUID interactionId,
            String interactionTitle,
            String stageName,
            String status,
            OffsetDateTime createdAt
    ) {
    }

    public record Responsible(UUID id, String displayName) {
    }

    public record LinkOptions(
            List<LinkedInteraction> interactions,
            List<LinkedAttachment> attachments,
            List<Responsible> responsibles
    ) {
    }

    public record ConfirmationQuery(
            UUID organizationId,
            UUID agreementId,
            UUID kindId,
            LocalDate from,
            LocalDate to
    ) {
    }

    public record Confirmation(
            UUID organizationId,
            String organizationName,
            UUID agreementId,
            String agreementNumber,
            UUID activityId,
            String activityTitle,
            UUID kindId,
            String kindName,
            LocalDate activityStart,
            LocalDate activityEnd,
            UUID attachmentId,
            String originalName,
            long sizeBytes,
            String status,
            OffsetDateTime createdAt,
            String interactionTitle
    ) {
    }

    public record ActivityDeleted(UUID id) {
    }
}
