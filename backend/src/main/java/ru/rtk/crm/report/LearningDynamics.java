package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LearningDynamics(
        OffsetDateTime generatedAt,
        LocalDate from,
        LocalDate to,
        String timezone,
        SeriesBy seriesBy,
        List<String> notes,
        List<Month> months,
        List<Series> series,
        List<Long> totalParticipants,
        List<Long> totalCompleted,
        List<Row> rows
) {
    public static final String TITLE = "Динамика обучения по вузам и ИТ-программам";

    public enum SeriesBy {
        ORGANIZATION,
        PROGRAM
    }

    public record Month(String key, String label, LocalDate asOf) {
    }

    public record Series(String key, String label, List<Long> participants, List<Long> completed) {
    }

    public record Row(
            String month,
            String monthLabel,
            UUID organizationId,
            String organizationName,
            UUID programId,
            String programName,
            long runs,
            long participants,
            Long completed,
            Long completionPercent
    ) {
    }
}
