package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.report.ReportStorage;

@Service
public class RetentionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionService.class);
    private static final int BATCH = 200;

    private final RetentionProperties properties;
    private final PersonalDataRepository repository;
    private final PersonalDataService personalDataService;
    private final AuditJournalRepository auditJournalRepository;
    private final ReportStorage reportStorage;
    private final ReentrantLock lock = new ReentrantLock();

    public RetentionService(
            RetentionProperties properties,
            PersonalDataRepository repository,
            PersonalDataService personalDataService,
            AuditJournalRepository auditJournalRepository,
            ReportStorage reportStorage
    ) {
        this.properties = properties;
        this.repository = repository;
        this.personalDataService = personalDataService;
        this.auditJournalRepository = auditJournalRepository;
        this.reportStorage = reportStorage;
    }

    @Scheduled(cron = "${app.retention.cron}", zone = "Europe/Moscow")
    public void applyScheduled() {
        if (!lock.tryLock()) {
            LOGGER.info("Scheduled retention skipped: the previous run is still in progress");
            return;
        }
        try {
            LOGGER.info("Retention applied: {}", apply(null, null).summary());
        } finally {
            lock.unlock();
        }
    }

    public RetentionRun run(CrmProfile actor, String requestId) {
        AdminAuthorization.requireAdmin(actor);
        if (!lock.tryLock()) {
            throw PrivacyException.retentionRunning();
        }
        try {
            return apply(actor.id(), requestId);
        } finally {
            lock.unlock();
        }
    }

    public RetentionPolicy policy(CrmProfile actor) {
        AdminAuthorization.requireAdmin(actor);
        return new RetentionPolicy(
                properties.reportFiles().toDays(),
                properties.inactiveContacts().toDays(),
                properties.dismissedProfiles().toDays(),
                properties.auditEvents().toDays(),
                properties.cron(),
                auditJournalRepository.findLatest(AuditAction.RETENTION_APPLIED).orElse(null)
        );
    }

    static List<String> policyLines(RetentionProperties properties) {
        return List.of(
                "Файлы отчётов удаляются через " + properties.reportFiles().toDays() + " дн. после формирования",
                "Контакты представителей вуза обезличиваются, если по вузу " + properties.inactiveContacts().toDays()
                        + " дн. нет изменений работ и контактов",
                "Профили сотрудников с закрытым доступом обезличиваются через " + properties.dismissedProfiles().toDays()
                        + " дн. после блокировки",
                "Записи журнала администратора и безопасности (скачивания, действия с ПДн) удаляются через "
                        + properties.auditEvents().toDays() + " дн.",
                "Сроки установлены по умолчанию до решения оператора ПДн и задаются конфигурацией сервера"
        );
    }

    private RetentionRun apply(UUID actorId, String requestId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> reportFiles = repository.expireReportResults(
                now.minus(properties.reportFiles()),
                "Файл удалён по сроку хранения (" + properties.reportFiles().toDays() + " дн.); сформируйте отчёт заново"
        );
        reportFiles.forEach(key -> reportStorage.delete(reportStorage.resultFile(key)));
        int contacts = 0;
        List<UUID> contactIds;
        do {
            contactIds = repository.findInactiveContactIds(now.minus(properties.inactiveContacts()), BATCH);
            if (!contactIds.isEmpty()) {
                contacts += personalDataService.anonymizeForRetention(contactIds, List.of()).contacts();
            }
        } while (contactIds.size() == BATCH);
        int profiles = 0;
        List<UUID> profileIds;
        do {
            profileIds = repository.findDismissedProfileIds(now.minus(properties.dismissedProfiles()), BATCH);
            if (!profileIds.isEmpty()) {
                profiles += personalDataService.anonymizeForRetention(List.of(), profileIds).profiles();
            }
        } while (profileIds.size() == BATCH);
        int auditEvents = auditJournalRepository.deleteOlderThan(now.minus(properties.auditEvents()));
        RetentionRun run = new RetentionRun(now, reportFiles.size(), contacts, profiles, auditEvents);
        auditJournalRepository.record(AuditAction.RETENTION_APPLIED, actorId, "RETENTION", null, null, run.summary(), requestId);
        return run;
    }
}
