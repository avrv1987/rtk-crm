package ru.rtk.crm.source;

import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronTrigger;

@Configuration
@EnableScheduling
@Profile("!demo-bootstrap")
class SourceSyncScheduling {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private final TaskScheduler taskScheduler;
    private final SourceSettingsService settings;
    private final SourceSyncService sourceSyncService;
    private ScheduledFuture<?> scheduled;

    SourceSyncScheduling(TaskScheduler taskScheduler, SourceSettingsService settings, SourceSyncService sourceSyncService) {
        this.taskScheduler = taskScheduler;
        this.settings = settings;
        this.sourceSyncService = sourceSyncService;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof WebServerApplicationContext) {
            reschedule();
        }
    }

    @EventListener
    public void onSettingsChanged(SourceSettingsChanged event) {
        reschedule();
    }

    synchronized void reschedule() {
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        String cron = settings.syncCron();
        scheduled = cron == null ? null : taskScheduler.schedule(sourceSyncService::startScheduled, new CronTrigger(cron, ZONE));
    }
}
