package ru.rtk.crm.work;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.work")
public record WorkProperties(
        int stuckDays,
        int upcomingDays,
        int licenseYearsAhead,
        List<String> trainingStageNames,
        int trainingCycleYears,
        int trainingNoticeDays,
        int reminderListLimit
) {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    public int licenseExpiresBy(OffsetDateTime now) {
        return now.atZoneSameInstant(ZONE).getYear() + licenseYearsAhead;
    }
}
