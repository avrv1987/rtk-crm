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
        REVIEW("На согласовании"),
        CORRECTION("На корректировке"),
        SIGNING("На подписании"),
        ACTIVE("Действует"),
        COMPLETED("Завершён"),
        TERMINATED("Расторгнут");

        private final String title;

        AgreementStatus(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public boolean signed() {
            return this == ACTIVE || this == COMPLETED || this == TERMINATED;
        }

        // Статус по сроку не меняется (решение владельца): истёкший договор остаётся «Действует» до перехода КАМ,
        // а признак «срок истёк» вычисляется по дате окончания на сегодня по МСК.
        public boolean expired(LocalDate validUntil, LocalDate today) {
            return this == ACTIVE && validUntil != null && validUntil.isBefore(today);
        }

        public List<TransitionOption> transitions() {
            return switch (this) {
                case DRAFT -> List.of(TransitionOption.forward(REVIEW));
                case REVIEW -> List.of(TransitionOption.forward(CORRECTION), TransitionOption.forward(SIGNING),
                        TransitionOption.back(DRAFT));
                case CORRECTION -> List.of(TransitionOption.back(REVIEW), TransitionOption.forward(SIGNING));
                case SIGNING -> List.of(TransitionOption.forward(ACTIVE), TransitionOption.back(CORRECTION));
                case ACTIVE -> List.of(TransitionOption.forward(COMPLETED), TransitionOption.back(TERMINATED));
                case COMPLETED -> List.of(TransitionOption.back(ACTIVE));
                case TERMINATED -> List.of();
            };
        }
    }

    // Сторона, у которой сейчас договор: «На согласовании» — у вуза, «На корректировке» — у школы (решение владельца),
    // «На подписании» — у подписантов обеих сторон, порога стороны нет.
    public enum ApprovalSide {
        UNIVERSITY,
        SCHOOL,
        SIGNING
    }

    public enum ApprovalColor {
        NORMAL,
        YELLOW,
        RED
    }

    public record Approval(
            ApprovalSide side,
            int round,
            long sideDays,
            Integer sideLimitDays,
            long totalDays,
            ApprovalColor color
    ) {
        public boolean delayed() {
            return color != ApprovalColor.NORMAL;
        }
    }

    public record StatusEntry(AgreementStatus toStatus, OffsetDateTime occurredAt) {
    }

    public enum SupplementStatus {
        DRAFT("Проект"),
        SIGNED("Подписано");

        private final String title;

        SupplementStatus(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public enum StageRole {
        EXCHANGE,
        CORRECTION,
        SIGNING
    }

    public enum PlanKind {
        SIGNING("Подписание"),
        RENEWAL("Продление");

        private final String title;

        PlanKind(String title) {
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

    public record TransitionOption(AgreementStatus toStatus, String title, boolean commentRequired) {
        static TransitionOption forward(AgreementStatus status) {
            return new TransitionOption(status, status.title(), false);
        }

        static TransitionOption back(AgreementStatus status) {
            return new TransitionOption(status, status.title(), true);
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
            LocalDate sentOn,
            PlanKind plannedKind,
            LocalDate plannedOn,
            AgreementStatus status,
            OffsetDateTime statusChangedAt,
            int activityCount,
            int confirmationCount,
            int version,
            List<TransitionOption> transitions
    ) {
    }

    public record Agreement(
            UUID id,
            UUID organizationId,
            String organizationName,
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            PlanKind plannedKind,
            LocalDate plannedOn,
            String parties,
            AgreementStatus status,
            OffsetDateTime statusChangedAt,
            Party universitySigner,
            Party schoolSigner,
            List<LinkedInteraction> interactions,
            List<Party> programs,
            List<Party> products,
            List<Party> directions,
            AgreementFile currentFile,
            List<AgreementFile> files,
            List<Supplement> supplements,
            List<StatusEvent> history,
            List<TransitionOption> transitions,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<Activity> activities,
            Approval approval
    ) {
    }

    public record AgreementRequest(
            @Min(0) Integer version,
            @NotBlank @Size(max = 100) String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            @Size(max = 2000) String parties,
            AgreementStatus status,
            UUID fileAttachmentId,
            PlanKind plannedKind,
            LocalDate plannedOn,
            UUID universitySignerContactId,
            UUID schoolSignerProfileId,
            @Size(max = 100) List<UUID> interactionIds,
            @Size(max = 200) List<UUID> programIds,
            @Size(max = 200) List<UUID> productIds,
            @Size(max = 200) List<UUID> directionIds
    ) {
    }

    public record TransitionRequest(
            @NotNull @Min(0) Integer version,
            @NotNull AgreementStatus toStatus,
            @Size(max = 2000) String comment,
            LocalDate signedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            UUID interactionId
    ) {
    }

    public record Party(UUID id, String name) {
    }

    public record AgreementFile(
            UUID id,
            UUID agreementId,
            UUID supplementId,
            int versionNumber,
            String originalName,
            String mediaType,
            long sizeBytes,
            String status,
            boolean downloadable,
            String comment,
            String createdByName,
            OffsetDateTime createdAt,
            String interactionTitle,
            boolean signed
    ) {
        AgreementFile markSigned() {
            return new AgreementFile(id, agreementId, supplementId, versionNumber, originalName, mediaType, sizeBytes, status,
                    downloadable, comment, createdByName, createdAt, interactionTitle, true);
        }
    }

    public record Supplement(
            UUID id,
            UUID agreementId,
            String number,
            LocalDate concludedOn,
            String subject,
            SupplementStatus status,
            int version,
            AgreementFile currentFile,
            List<AgreementFile> files
    ) {
    }

    public record SupplementRequest(
            @Min(0) Integer version,
            @NotBlank @Size(max = 100) String number,
            LocalDate concludedOn,
            @Size(max = 2000) String subject,
            @NotNull SupplementStatus status
    ) {
    }

    public record StatusEvent(
            UUID id,
            AgreementStatus fromStatus,
            AgreementStatus toStatus,
            String comment,
            String actorName,
            OffsetDateTime occurredAt,
            String interactionTitle
    ) {
    }

    public record SignerContact(UUID id, String name, String position, boolean signatory) {
    }

    public record WorkAgreements(
            UUID interactionId,
            UUID organizationId,
            boolean signingRuleEnabled,
            List<WorkStage> stages,
            List<AgreementSummary> agreements
    ) {
    }

    public record WorkStage(UUID stageId, String name, StageRole role) {
    }

    public record RegistryQuery(
            List<AgreementStatus> statuses,
            UUID organizationId,
            UUID managerId,
            LocalDate expiresBefore,
            Integer reviewLongerThanDays,
            boolean delayed,
            UUID teamId
    ) {
        public RegistryQuery(List<AgreementStatus> statuses, UUID organizationId, UUID managerId, LocalDate expiresBefore,
                             Integer reviewLongerThanDays, boolean delayed) {
            this(statuses, organizationId, managerId, expiresBefore, reviewLongerThanDays, delayed, null);
        }
    }

    public record RegistryRow(
            UUID id,
            UUID organizationId,
            String organizationName,
            String number,
            AgreementStatus status,
            OffsetDateTime statusChangedAt,
            long daysInStatus,
            LocalDate sentOn,
            LocalDate concludedOn,
            LocalDate validUntil,
            String subject,
            UUID managerId,
            String managerName,
            Approval approval,
            UUID teamId,
            String teamName
    ) {
        public RegistryRow(UUID id, UUID organizationId, String organizationName, String number, AgreementStatus status,
                           OffsetDateTime statusChangedAt, long daysInStatus, LocalDate sentOn, LocalDate concludedOn,
                           LocalDate validUntil, String subject, UUID managerId, String managerName, Approval approval) {
            this(id, organizationId, organizationName, number, status, statusChangedAt, daysInStatus, sentOn, concludedOn, validUntil,
                    subject, managerId, managerName, approval, null, null);
        }
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
            List<Responsible> responsibles,
            List<SignerContact> contacts,
            List<Responsible> schoolSigners,
            List<Party> programs,
            List<Party> products,
            List<Party> directions
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

    public record PeriodReport(
            OffsetDateTime generatedAt,
            LocalDate from,
            LocalDate to,
            Integer longerThanDays,
            List<String> notes,
            PeriodTotals totals,
            List<PeriodTransition> transitions,
            List<RegistryRow> signed,
            List<RegistryRow> terminated,
            List<RegistryRow> longReview
    ) {
    }

    public record PeriodTotals(
            int transitions,
            int signed,
            int terminated,
            Double averageApprovalDays,
            int approvalSamples,
            int longReview
    ) {
    }

    public record PeriodTransition(
            OffsetDateTime occurredAt,
            UUID agreementId,
            String organizationName,
            String number,
            AgreementStatus fromStatus,
            AgreementStatus toStatus,
            String actorName,
            String comment
    ) {
    }
}
