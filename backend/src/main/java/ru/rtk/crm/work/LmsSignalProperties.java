package ru.rtk.crm.work;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.lms-signals")
public record LmsSignalProperties(
        String classesStageName,
        int noStudentsAfterDays,
        int lowCompletionPercent,
        int completionWindowDays
) {
}
