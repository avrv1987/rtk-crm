package ru.rtk.crm.work;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.lms-signals")
public record LmsSignalProperties(
        String classesStageName,
        int noStudentsAfterDays,
        int lowCompletionPercent,
        int completionWindowDays,
        int activityGraceDays,
        int lowActivityPercent,
        int notStartedPercent,
        int dropoutPercent,
        int inactiveAfterDays
) {
    public static final int TEACHER_INACTIVE_DAYS = 14;
}
