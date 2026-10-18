package ru.rtk.crm.work;

import java.time.Duration;

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
        int inactiveAfterDays,
        Duration staleAfter,
        Integer completionCoveragePercent
) {
    public static final int TEACHER_INACTIVE_DAYS = 14;
    public static final Duration DEFAULT_STALE_AFTER = Duration.ofHours(26);
    public static final int DEFAULT_COMPLETION_COVERAGE_PERCENT = 70;

    public LmsSignalProperties {
        staleAfter = staleAfter == null ? DEFAULT_STALE_AFTER : staleAfter;
        completionCoveragePercent = completionCoveragePercent == null ? DEFAULT_COMPLETION_COVERAGE_PERCENT : completionCoveragePercent;
    }
}
