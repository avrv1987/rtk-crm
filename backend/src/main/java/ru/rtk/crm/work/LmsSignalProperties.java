package ru.rtk.crm.work;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.rtk.crm.settings.SystemSetting;
import ru.rtk.crm.settings.SystemSettings;

@ConfigurationProperties("app.lms-signals")
public record LmsSignalProperties(
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

    /** Пороги с учётом правки администратора в «Настройках»: устаревание данных и покрытие статусов завершения. */
    public LmsSignalProperties with(SystemSettings settings) {
        return new LmsSignalProperties(noStudentsAfterDays, lowCompletionPercent, completionWindowDays, activityGraceDays,
                lowActivityPercent, notStartedPercent, dropoutPercent, inactiveAfterDays,
                settings.hours(SystemSetting.SOURCES_STALE_HOURS, staleAfter),
                settings.value(SystemSetting.LMS_COMPLETION_COVERAGE, completionCoveragePercent));
    }
}
