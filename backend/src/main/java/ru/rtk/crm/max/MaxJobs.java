package ru.rtk.crm.max;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Задания модуля в общем планировщике CRM. Каждое ловит свои ошибки: сбой MAX не должен останавливать повторы.
// app.max.jobs-enabled=false выключает их в тестах, где шаги вызываются напрямую.
@Component
@MaxEnabled
class MaxJobs {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxJobs.class);

    private final MaxProperties properties;
    private final MaxInboxProcessor inbox;
    private final MaxOutbox outbox;
    private final MaxNotifications notifications;
    private final MaxSettingsService settings;
    private final MaxQueueRepository queueRepository;
    private final MaxLinkRepository linkRepository;
    private final MaxSessionRepository sessionRepository;
    private final String environmentToken;

    MaxJobs(
            MaxProperties properties,
            MaxInboxProcessor inbox,
            MaxOutbox outbox,
            MaxNotifications notifications,
            MaxSettingsService settings,
            MaxQueueRepository queueRepository,
            MaxLinkRepository linkRepository,
            MaxSessionRepository sessionRepository,
            @Value("${app.max.bot-token:}") String environmentToken
    ) {
        this.properties = properties;
        this.inbox = inbox;
        this.outbox = outbox;
        this.notifications = notifications;
        this.settings = settings;
        this.queueRepository = queueRepository;
        this.linkRepository = linkRepository;
        this.sessionRepository = sessionRepository;
        this.environmentToken = environmentToken;
    }

    // Начальная настройка из MAX_BOT_TOKEN сразу при старте, не дожидаясь ежечасной проверки подписки.
    @EventListener(ApplicationReadyEvent.class)
    void bootstrap() {
        run("bootstrap", () -> settings.bootstrap(environmentToken));
    }

    @Scheduled(fixedDelayString = "${app.max.inbox-delay}", initialDelayString = "${app.max.inbox-delay}")
    void processInbox() {
        run("inbox", inbox::processBatch);
    }

    @Scheduled(fixedDelayString = "${app.max.sender-delay}", initialDelayString = "${app.max.sender-delay}")
    void sendOutbox() {
        run("outbox", outbox::sendBatch);
    }

    @Scheduled(fixedDelayString = "${app.max.collector-delay}", initialDelayString = "${app.max.collector-delay}")
    void collectEvents() {
        run("collector", notifications::collectEvents);
    }

    @Scheduled(cron = "${app.max.digest-cron}", zone = "Europe/Moscow")
    void morning() {
        run("morning", notifications::morning);
    }

    @Scheduled(fixedDelayString = "${app.max.watch-delay}", initialDelayString = "PT2M")
    void watchSubscription() {
        run("subscription", () -> {
            settings.bootstrap(environmentToken);
            settings.watch();
        });
    }

    // Сроки хранения модуля: входящие — 7 дней, исходящие — 30 дней, коды и сеансы — после истечения.
    @Scheduled(cron = "${app.max.retention-cron}", zone = "Europe/Moscow")
    void applyRetention() {
        run("retention", () -> {
            OffsetDateTime now = OffsetDateTime.now();
            int inboxRows = queueRepository.deleteInboxBefore(now.minus(properties.inboxRetention()));
            int outboxRows = queueRepository.deleteOutboxBefore(now.minus(properties.outboxRetention()));
            int codes = linkRepository.deleteExpiredCodes(now);
            int sessions = sessionRepository.deleteStale(now, now.minus(properties.sessionIdle()));
            LOGGER.info("MAX retention applied inbox={} outbox={} codes={} sessions={}", inboxRows, outboxRows, codes, sessions);
        });
    }

    void run(String name, Runnable step) {
        if (!properties.jobsEnabled()) {
            return;
        }
        try {
            step.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("MAX job failed job={}", name, exception);
        }
    }
}
