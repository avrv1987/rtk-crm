package ru.rtk.crm.partner;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.attachment.AttachmentStorage;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.partner.PartnerCabinetRepository.DownloadRow;
import ru.rtk.crm.partner.PartnerCabinetRepository.OrganizationRow;
import ru.rtk.crm.partner.PartnerCabinetRepository.StageRow;
import ru.rtk.crm.partner.PartnerModels.PartnerAgreement;
import ru.rtk.crm.partner.PartnerModels.PartnerCabinet;
import ru.rtk.crm.partner.PartnerModels.PartnerManager;
import ru.rtk.crm.partner.PartnerModels.PartnerNextStep;
import ru.rtk.crm.partner.PartnerModels.PartnerOrganization;
import ru.rtk.crm.partner.PartnerModels.PartnerStage;
import ru.rtk.crm.partner.PartnerModels.PartnerWork;

@Service
public class PartnerCabinetService {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final PartnerCabinetRepository partnerCabinetRepository;
    private final AttachmentStorage attachmentStorage;
    private final AuditJournalRepository auditJournalRepository;

    public PartnerCabinetService(
            PartnerCabinetRepository partnerCabinetRepository,
            AttachmentStorage attachmentStorage,
            AuditJournalRepository auditJournalRepository
    ) {
        this.partnerCabinetRepository = partnerCabinetRepository;
        this.attachmentStorage = attachmentStorage;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public PartnerCabinet cabinet(CrmProfile partner) {
        UUID organizationId = requireOrganization(partner);
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
                        agreement.status()
                ))
                .toList();
        return new PartnerCabinet(
                new PartnerOrganization(organization.id(), organization.name(), organization.type()),
                organization.managerName() == null ? null : new PartnerManager(organization.managerName()),
                works,
                partnerCabinetRepository.findDocuments(organizationId),
                agreements
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

    private UUID requireOrganization(CrmProfile partner) {
        return partnerCabinetRepository.findOrganizationId(partner.id()).orElseThrow(PartnerException::accessClosed);
    }

    private static LocalDate passedOn(StageRow stage) {
        return stage.completedOn() != null ? stage.completedOn() : stage.leftAt().atZoneSameInstant(MOSCOW).toLocalDate();
    }

    public record PartnerDownload(DownloadRow document, InputStream content) {
    }
}
