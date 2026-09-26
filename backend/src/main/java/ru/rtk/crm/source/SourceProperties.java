package ru.rtk.crm.source;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.sources")
public record SourceProperties(
        int slots,
        int queueCapacity,
        String syncCron,
        Duration staleAfter,
        Website website,
        Moodle moodle
) {
    public boolean scheduled() {
        return syncCron != null && !syncCron.isBlank() && !syncCron.strip().equals("-");
    }

    public record Website(
            String baseUrl,
            String token,
            Duration connectTimeout,
            Duration readTimeout,
            int maxPages,
            DataSize maxPageSize
    ) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record Moodle(
            String baseUrl,
            String token,
            List<Long> courseIds,
            List<String> studentRoles,
            List<String> teacherRoles,
            Duration connectTimeout,
            Duration readTimeout,
            DataSize maxResponseSize
    ) {
        public Moodle {
            courseIds = courseIds == null ? List.of() : List.copyOf(courseIds);
            studentRoles = studentRoles == null ? List.of() : List.copyOf(studentRoles);
            teacherRoles = teacherRoles == null ? List.of() : List.copyOf(teacherRoles);
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank() && !courseIds.isEmpty();
        }
    }
}
