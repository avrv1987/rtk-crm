package ru.rtk.crm.partner;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.access.AccountSyncException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.partner.PartnerCabinetRepository.DownloadRow;
import ru.rtk.crm.partner.PartnerCabinetRepository.OrganizationRow;
import ru.rtk.crm.partner.PartnerCabinetRepository.StageRow;
import ru.rtk.crm.agreement.AgreementApproval;
import ru.rtk.crm.partner.PartnerModels.PartnerAgreement;
import ru.rtk.crm.partner.PartnerModels.PartnerCabinet;
import ru.rtk.crm.partner.PartnerModels.PartnerManager;
import ru.rtk.crm.partner.PartnerModels.PartnerNextStep;
import ru.rtk.crm.partner.PartnerModels.PartnerOrganization;
import ru.rtk.crm.partner.PartnerModels.PartnerStage;
import ru.rtk.crm.partner.PartnerModels.PartnerWork;

@Service
public class PartnerCabinetService {
    static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Logger LOGGER = LoggerFactory.getLogger(PartnerCabinetService.class);

    private final PartnerCabinetRepository partnerCabinetRepository;
    private final AttachmentStorage attachmentStorage;
    private final AuditJournalRepository auditJournalRepository;
    private final KeycloakAccountClient keycloakAccountClient;
    private final PartnerRequestService partnerRequestService;

    public PartnerCabinetService(
            PartnerCabinetRepository partnerCabinetRepository,
            AttachmentStorage attachmentStorage,
            AuditJournalRepository auditJournalRepository,
            KeycloakAccountClient keycloakAccountClient,
            PartnerRequestService partnerRequestService
    ) {
        this.partnerCabinetRepository = partnerCabinetRepository;
        this.attachmentStorage = attachmentStorage;
        this.auditJournalRepository = auditJournalRepository;
        this.keycloakAccountClient = keycloakAccountClient;
        this.partnerRequestService = partnerRequestService;
    }

    @Transactional(readOnly = true)
    public PartnerCabinet cabinet(CrmProfile partner) {
        PartnerCabinetRepository.PartnerScope scope = partnerCabinetRepository.findScope(partner.id())
                .orElseThrow(PartnerException::accessClosed);
        UUID organizationId = scope.organizationId();
        Map<UUID, OffsetDateTime> receipts = partnerRequestService.receipts(partner.id());
        OrganizationRow organization = partnerCabinetRepository.findOrganization(organizationId);
        Map<UUID, List<String>> products = partnerCabinetRepository.findProducts(organizationId).stream()
                .collect(Collectors.groupingBy(
                        PartnerCabinetRepository.ProductRow::interactionId,
                        Collectors.mapping(PartnerCabinetRepository.ProductRow::name, Collectors.toList())
                ));
        Map<UUID, List<PartnerStage>> passedStages = partnerCabinetRepository.findStagesBeforeCurrent(organizationId).stream()
                .filter(stage -> stage.completedOn() != null || stage.leftAt() != null)
                .collect(Collectors.groupingBy(
                        StageRow::interactionId,
                        Collectors.mapping(stage -> new PartnerStage(stage.name(), passedOn(stage)), Collectors.toList())
                ));
        List<PartnerWork> works = partnerCabinetRepository.findWorks(organizationId).stream()
                .map(work -> new PartnerWork(
                        work.id(),
                        work.title(),
                        work.programName(),
                        products.getOrDefault(work.id(), List.of()),
                        work.stageName(),
                        work.status(),
                        passedStages.getOrDefault(work.id(), List.of()),
                        work.nextStepPartnerVisible() && work.nextAction() != null
                                ? new PartnerNextStep(work.nextAction(), work.nextActionAt())
                                : null
                ))
                .toList();
        Map<UUID, List<String>> subjects = partnerCabinetRepository.findAgreementActivities(organizationId).stream()
                .collect(Collectors.groupingBy(
                        PartnerCabinetRepository.ActivityRow::agreementId,
                        Collectors.mapping(PartnerCabinetRepository.ActivityRow::title, Collectors.toList())
                ));
        List<PartnerAgreement> agreements = partnerCabinetRepository.findAgreements(organizationId).stream()
                .map(agreement -> new PartnerAgreement(
                        agreement.id(),
                        agreement.number(),
                        subjects.getOrDefault(agreement.id(), List.of()),
                        agreement.concludedOn(),
                        agreement.validUntil(),
                        agreement.status(),
                        agreement.statusChangedAt(),
                        // Круг согласования — как в карточке договора у школы (AgreementApproval): номер прохода «На согласовании».
                        AgreementApproval.inProgress(agreement.status()) ? Math.max(1, agreement.reviewRounds()) : null
                ))
                .toList();
        return new PartnerCabinet(
                new PartnerOrganization(organization.id(), organization.name(), organization.type()),
                organization.managerName() == null ? null : manager(organization),
                works,
                partnerCabinetRepository.findDocuments(organizationId).stream()
                        .map(document -> document.withReceivedAt(receipts.get(document.id())))
                        .toList(),
                agreements,
                partnerRequestService.forPartner(organizationId, scope.contactId(), partner.id())
        );
    }

    @Transactional
    public PartnerDownload download(CrmProfile partner, UUID attachmentId, String requestId) {
        UUID organizationId = requireOrganization(partner);
        DownloadRow document = partnerCabinetRepository.findDownloadable(organizationId, attachmentId)
                .orElseThrow(PartnerException::documentNotFound);
        auditJournalRepository.recordAttachmentAccess(AuditAction.ATTACHMENT_DOWNLOADED, partner.id(), document.id(), requestId);
        return new PartnerDownload(document, attachmentStorage.open(document.storageKey()));
    }

    // Свой файл ответа координатор скачивает из кабинета, пока доступ открыт; в журнал — как любое скачивание вложения.
    @Transactional
    public PartnerDownload downloadOwnResponse(CrmProfile partner, UUID responseId, String requestId) {
        PartnerCabinetRepository.PartnerScope scope = partnerCabinetRepository.findScope(partner.id())
                .orElseThrow(PartnerException::accessClosed);
        DownloadRow document = partnerRequestService.ownResponseFile(responseId, partner.id(), scope.organizationId(), scope.contactId())
                .orElseThrow(PartnerException::documentNotFound);
        auditJournalRepository.recordAttachmentAccess(AuditAction.ATTACHMENT_DOWNLOADED, partner.id(), document.id(), requestId);
        return new PartnerDownload(document, attachmentStorage.open(document.storageKey()));
    }

    // Почта и телефон сотрудника хранятся только в Keycloak, в профиле CRM их нет. Если Keycloak недоступен,
    // кабинет открывается с именем КАМ без контактов.
    private PartnerManager manager(OrganizationRow organization) {
        Optional<KeycloakAccountClient.UserContact> contact;
        try {
            contact = keycloakAccountClient.contact(organization.managerSubject());
        } catch (AccountSyncException exception) {
            LOGGER.warn("Partner cabinet manager contact is unavailable: {}", exception.getMessage());
            contact = Optional.empty();
        }
        return new PartnerManager(
                organization.managerName(),
                contact.map(KeycloakAccountClient.UserContact::email).orElse(null),
                contact.map(KeycloakAccountClient.UserContact::phone).orElse(null)
        );
    }

    private UUID requireOrganization(CrmProfile partner) {
        return partnerCabinetRepository.findOrganizationId(partner.id()).orElseThrow(PartnerException::accessClosed);
    }

    private static LocalDate passedOn(StageRow stage) {
        return stage.completedOn() != null ? stage.completedOn() : stage.leftAt().atZoneSameInstant(MOSCOW).toLocalDate();
    }

    public record PartnerDownload(DownloadRow document, InputStream content) {
    }
}
