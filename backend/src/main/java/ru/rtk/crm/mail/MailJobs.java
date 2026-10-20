package ru.rtk.crm.mail;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.rtk.crm.settings.SystemSetting;
import ru.rtk.crm.settings.SystemSettings;

// Задания модуля в общем планировщике CRM. Каждое ловит свои ошибки: сбой почтового сервера не должен останавливать
// повторы. app.mail.jobs-enabled=false выключает их в тестах, где шаги вызываются напрямую.
@Component
@MailEnabled
class MailJobs {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailJobs.class);

    private final MailProperties properties;
    private final MailOutbox outbox;
    private final MailNotifications notifications;
    private final MailRepository repository;
    private final SystemSettings systemSettings;

    MailJobs(MailProperties properties, MailOutbox outbox, MailNotifications notifications, MailRepository repository,
             SystemSettings systemSettings) {
        this.properties = properties;
        this.outbox = outbox;
        this.notifications = notifications;
        this.repository = repository;
        this.systemSettings = systemSettings;
    }

    @Scheduled(fixedDelayString = "${app.mail.sender-delay}", initialDelayString = "${app.mail.sender-delay}")
    void sendOutbox() {
        run("outbox", outbox::sendBatch);
    }

    @Scheduled(fixedDelayString = "${app.mail.collector-delay}", initialDelayString = "${app.mail.collector-delay}")
    void collectEvents() {
        run("collector", notifications::collectEvents);
    }

    @Scheduled(cron = "${app.mail.digest-cron}", zone = "Europe/Moscow")
    void morning() {
        run("morning", notifications::morning);
    }

    @Scheduled(cron = "${app.mail.weekly-cron}", zone = "Europe/Moscow")
    void weekly() {
        run("weekly", notifications::weekly);
    }

    // Журнал писем хранится app.mail.retention (30 дней) или сколько задал администратор в «Настройках»; приглашения
    // удаляются через неделю после истечения.
    @Scheduled(cron = "${app.mail.retention-cron}", zone = "Europe/Moscow")
    void applyRetention() {
        run("retention", () -> {
            OffsetDateTime now = OffsetDateTime.now();
            int letters = repository.deleteOutboxBefore(now.minus(
                    systemSettings.days(SystemSetting.RETENTION_MAIL_LETTERS, properties.retention())));
            int invitations = repository.deleteInvitationsBefore(now.minusDays(7));
            LOGGER.info("Mail retention applied letters={} invitations={}", letters, invitations);
        });
    }

    void run(String name, Runnable step) {
        if (!properties.jobsEnabled()) {
            return;
        }
        try {
            step.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Mail job failed job={}", name, exception);
        }
    }
}
