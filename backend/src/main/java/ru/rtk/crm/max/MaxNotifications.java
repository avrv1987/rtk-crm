package ru.rtk.crm.max;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkService;

// Сборщик уведомлений. События CRM читаются из таблиц истории с перекрытием окна: событие, записанное транзакцией,
// которая закоммитилась позже начала прошлого прохода, не теряется, а уникальный ключ события не даёт его продублировать.
// «Руководство» и менеджер учебного процесса уведомлений не получают (отбор в MaxNotificationRepository).
@Service
@MaxEnabled
public class MaxNotifications {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxNotifications.class);
    static final Duration OVERLAP = Duration.ofMinutes(10);
    static final int EXPIRY_NOTICE_DAYS = 30;
    static final int EXPIRY_LAST_NOTICE_DAYS = 7;

    private final MaxNotificationRepository repository;
    private final MaxSettingsRepository settingsRepository;
    private final MaxSettingsService settings;
    private final MaxOutbox outbox;
    private final WorkService workService;

    public MaxNotifications(
            MaxNotificationRepository repository,
            MaxSettingsRepository settingsRepository,
            MaxSettingsService settings,
            MaxOutbox outbox,
            WorkService workService
    ) {
        this.repository = repository;
        this.settingsRepository = settingsRepository;
        this.settings = settings;
        this.outbox = outbox;
        this.workService = workService;
    }

    // Первый проход после включения модуля не рассылает накопленную историю: окно начинается с текущего момента.
    public int collectEvents() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime collected = settingsRepository.find().eventsCollectedUntil();
        if (collected == null) {
            settingsRepository.saveCollectedUntil(now);
            return 0;
        }
        OffsetDateTime since = collected.minus(OVERLAP);
        int queued = 0;
        for (MaxNotificationRepository.Event event : repository.assignments(since)) {
            queued += enqueue("assign:" + event.eventId(), event, MaxMessageKind.ASSIGNMENT,
                    MaxDigestFormatter.assignment(event.organizationName()));
        }
        for (MaxNotificationRepository.Event event : repository.agreementCorrections(since)) {
            queued += enqueue("agreement-correction:" + event.eventId(), event, MaxMessageKind.AGREEMENT_CORRECTION,
                    MaxDigestFormatter.agreementCorrection(event.number(), event.organizationName()));
        }
        for (MaxNotificationRepository.Event event : repository.siteRequests(since)) {
            queued += enqueue("site:" + event.eventId() + ":" + event.profileId(), event, MaxMessageKind.SITE_REQUEST,
                    MaxDigestFormatter.siteRequest(event.organizationName()));
        }
        // Срок сообщения — срок плана: после него «Записать» уже не сработает.
        for (MaxNotificationRepository.AgentPlanEvent plan : repository.agentPlans(since, now)) {
            MaxMessage message = new MaxMessage(MaxDigestFormatter.agentPlan(plan.organizationName()),
                    List.of(settings.crmLink("Открыть план", "#/agent-plans/" + plan.planId())));
            if (outbox.enqueue("agent-plan:" + plan.planId(), plan.profileId(), plan.maxUserId(), MaxMessageKind.AGENT_PLAN,
                    plan.organizationId(), message, plan.expiresAt())) {
                queued++;
            }
        }
        settingsRepository.saveCollectedUntil(now);
        return queued;
    }

    // Утренний проход в 9:00 МСК: истекающие договоры и сводка. Сводка строится тем же WorkService.reminders, что и
    // колокольчик в CRM, поэтому числа совпадают с «Моей работой»; ошибка у одного сотрудника не останавливает остальных.
    public int morning() {
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = now.atZoneSameInstant(MaxProperties.ZONE).toLocalDate();
        OffsetDateTime endOfDay = today.plusDays(1).atStartOfDay(MaxProperties.ZONE).toOffsetDateTime();
        int queued = 0;
        for (MaxNotificationRepository.Event event : repository.expiringAgreements(today, today.plusDays(EXPIRY_NOTICE_DAYS))) {
            int bucket = event.validUntil().isAfter(today.plusDays(EXPIRY_LAST_NOTICE_DAYS)) ? EXPIRY_NOTICE_DAYS : EXPIRY_LAST_NOTICE_DAYS;
            queued += enqueue("agreement-expiry:" + event.eventId() + ":" + event.validUntil() + ":" + bucket, event,
                    MaxMessageKind.AGREEMENT_EXPIRY,
                    MaxDigestFormatter.agreementExpiry(event.number(), event.organizationName(), event.validUntil(), today));
        }
        List<MaxNotificationRepository.DigestRecipient> recipients = repository.digestRecipients();
        for (MaxNotificationRepository.DigestRecipient recipient : recipients) {
            try {
                ReminderDigest digest = workService.reminders(recipient.profile());
                String text = MaxDigestFormatter.digest(digest);
                if (text != null && outbox.enqueue("digest:" + recipient.profile().id() + ":" + today, recipient.profile().id(),
                        recipient.maxUserId(), MaxMessageKind.DIGEST, null,
                        new MaxMessage(text, List.of(settings.openButton("work", "#/work"))), endOfDay)) {
                    queued++;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("MAX digest was not built for profile={}", recipient.profile().id(), exception);
            }
        }
        return queued;
    }

    private int enqueue(String key, MaxNotificationRepository.Event event, MaxMessageKind kind, String text) {
        MaxMessage message = new MaxMessage(text,
                List.of(settings.openButton("work", "#/organizations/" + event.organizationId())));
        return outbox.enqueue(key, event.profileId(), event.maxUserId(), kind, event.organizationId(), message) ? 1 : 0;
    }
}
