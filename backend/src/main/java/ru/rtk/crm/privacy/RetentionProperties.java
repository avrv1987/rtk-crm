package ru.rtk.crm.privacy;

import java.time.Duration;
import java.time.Period;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.retention")
public record RetentionProperties(
        String cron,
        Duration reportFiles,
        Duration inactiveContacts,
        Duration dismissedProfiles,
        Duration auditEvents,
        Period learnerProfiles
) {
}
