package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
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
import ru.rtk.crm.enrolment.LearnerPrivacyService;
import ru.rtk.crm.report.ReportStorage;

@Service
public class RetentionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionService.class);
    private static final int BATCH = 200;
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final RetentionProperties properties;
    private final PersonalDataRepository repository;
    private final PersonalDataService personalDataService;
    private final LearnerPrivacyService learnerPrivacyService;
    private final AuditJournalRepository auditJournalRepository;
    private final ReportStorage reportStorage;
    private final ReentrantLock lock = new ReentrantLock();

    public RetentionService(
            RetentionProperties properties,
            PersonalDataRepository repository,
            PersonalDataService personalDataService,
            LearnerPrivacyService learnerPrivacyService,
            AuditJournalRepository auditJournalRepository,
            ReportStorage reportStorage
    ) {
        this.properties = properties;
        this.repository = repository;
        this.personalDataService = personalDataService;
        this.learnerPrivacyService = learnerPrivacyService;
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
            LearnerPrivacyService.Counters learners = learnerPrivacyService.counters();
            if (learners.nearLimit()) {
                LOGGER.warn("Learner profiles stored: {}; the fourth protection level requires fewer than {} subjects",
                        learners.profiles(), learners.profilesLimit());
            }
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
                term(properties.learnerProfiles()),
                properties.cron(),
                auditJournalRepository.findLatest(AuditAction.RETENTION_APPLIED).orElse(null),
                learnerPrivacyService.counters()
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
                "Анкеты слушателей обезличиваются через " + term(properties.learnerProfiles())
                        + " после окончания последнего потока слушателя; зачисления и оплаченные заявки остаются в статистике",
                "Сроки установлены по умолчанию до решения оператора ПДн и задаются конфигурацией сервера"
        );
    }

    static String term(Period period) {
        List<String> parts = new ArrayList<>();
        if (period.getYears() != 0) {
            parts.add(period.getYears() + " г.");
        }
        if (period.getMonths() != 0) {
            parts.add(period.getMonths() + " мес.");
        }
        if (period.getDays() != 0 || parts.isEmpty()) {
            parts.add(period.getDays() + " дн.");
        }
        return String.join(" ", parts);
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
        int learners = learnerPrivacyService.anonymizeExpired(
                properties.learnerProfiles(), now.atZoneSameInstant(MOSCOW).toLocalDate()
        );
        int auditEvents = auditJournalRepository.deleteOlderThan(now.minus(properties.auditEvents()));
        RetentionRun run = new RetentionRun(now, reportFiles.size(), contacts, profiles, learners, auditEvents);
        auditJournalRepository.record(AuditAction.RETENTION_APPLIED, actorId, "RETENTION", null, null, run.summary(), requestId);
        return run;
    }
}
