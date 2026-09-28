package ru.rtk.crm.partner;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.attachment.AttachmentKind;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionWorkStatus;

public final class PartnerModels {
    private PartnerModels() {
    }

    public record PartnerAccess(
            UUID profileId,
            UUID contactId,
            String contactName,
            String login,
            boolean active,
            boolean accountSyncRequired,
            OffsetDateTime updatedAt,
            String accountSyncError
    ) {
        PartnerAccess withAccountSyncError(String error) {
            return new PartnerAccess(profileId, contactId, contactName, login, active, accountSyncRequired, updatedAt, error);
        }
    }

    public record PartnerAccessGranted(PartnerAccess access, String login, String temporaryPassword) {
    }

    public record PartnerCabinet(
            PartnerOrganization organization,
            PartnerManager manager,
            List<PartnerWork> works,
            List<PartnerDocument> documents,
            List<PartnerAgreement> agreements
    ) {
    }

    public record PartnerOrganization(UUID id, String name, OrganizationType type) {
    }

    public record PartnerManager(String name) {
    }

    public record PartnerWork(
            UUID id,
            String title,
            String programName,
            List<String> productNames,
            String currentStageName,
            InteractionWorkStatus status,
            List<PartnerStage> passedStages,
            PartnerNextStep nextStep
    ) {
    }

    public record PartnerStage(String name, LocalDate passedOn) {
    }

    public record PartnerNextStep(String action, OffsetDateTime dueAt) {
    }

    public record PartnerDocument(
            UUID id,
            UUID workId,
            String workTitle,
            String name,
            AttachmentKind kind,
            String mediaType,
            long sizeBytes,
            boolean downloadable,
            OffsetDateTime createdAt
    ) {
    }

    public record PartnerAgreement(
            UUID id,
            String number,
            List<String> subject,
            LocalDate concludedOn,
            LocalDate validUntil,
            AgreementStatus status
    ) {
    }
}
