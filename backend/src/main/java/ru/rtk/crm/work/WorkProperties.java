package ru.rtk.crm.work;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.work")
public record WorkProperties(
        int stuckDays,
        int upcomingDays,
        int licenseYearsAhead,
        int trainingCycleYears,
        int trainingNoticeDays,
        int reminderListLimit,
        int qualificationAfterMonths,
        int agreementNoticeDays
) {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    public int licenseExpiresBy(OffsetDateTime now) {
        return now.atZoneSameInstant(ZONE).getYear() + licenseYearsAhead;
    }
}
