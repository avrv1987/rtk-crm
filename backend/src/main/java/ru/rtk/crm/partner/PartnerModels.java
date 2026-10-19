package ru.rtk.crm.partner;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
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

    // invitationSentTo — маска адреса, куда ушла ссылка на пароль; тогда temporaryPassword пустой.
    public record PartnerAccessGranted(PartnerAccess access, String login, String temporaryPassword, String invitationSentTo) {
    }

    public record PartnerCabinet(
            PartnerOrganization organization,
            PartnerManager manager,
            List<PartnerWork> works,
            List<PartnerDocument> documents,
            List<PartnerAgreement> agreements,
            List<PartnerRequestModels.PartnerRequestForPartner> requests
    ) {
    }

    public record PartnerOrganization(UUID id, String name, OrganizationType type) {
    }

    public record PartnerManager(String name, String email, String phone) {
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
            String kind,
            String kindName,
            String mediaType,
            long sizeBytes,
            boolean downloadable,
            OffsetDateTime createdAt,
            int revision,
            boolean replaced,
            OffsetDateTime receivedAt
    ) {
        PartnerDocument withReceivedAt(OffsetDateTime value) {
            return new PartnerDocument(id, workId, workTitle, name, kind, kindName, mediaType, sizeBytes, downloadable, createdAt, revision, replaced,
                    value);
        }
    }

    public record PartnerAgreement(
            UUID id,
            String number,
            List<String> subject,
            LocalDate concludedOn,
            LocalDate validUntil,
            AgreementStatus status,
            OffsetDateTime statusChangedAt,
            Integer approvalRound
    ) {
    }
}
