package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Покрытие данных обучения (разбор §5.4). Ожидаемые потоки — сопоставленные администратором запуски занятий студентов
// Moodle, которые идут сегодня; знаменатель не зависит от того, пришли ли по ним данные. Курсы, которые в Moodle есть,
// но в CRM ни разу не пришли, сюда не попадают: реестра плановых потоков вне Moodle в CRM нет.
public record LearningCoverage(
        OffsetDateTime calculatedAt,
        boolean sourceConfigured,
        long staleAfterHours,
        int expected,
        int fresh,
        int stale,
        int withoutObservation,
        Long unappliedRecords,
        List<Run> runs
) {
    public enum State { FRESH, STALE, NO_DATA }

    public record Run(
            UUID mappingId,
            String organizationName,
            String programName,
            String courseName,
            LocalDate runStartsOn,
            LocalDate runEndsOn,
            OffsetDateTime lastObservedAt,
            State state
    ) {
    }

    // Без подключённого Moodle свежесть не проверяется (как у отметки устаревания в карточке): поток с наблюдением
    // считается доступным, без наблюдения — «нет данных».
    static State state(OffsetDateTime lastObservedAt, OffsetDateTime staleBefore, boolean sourceConfigured) {
        if (lastObservedAt == null) {
            return State.NO_DATA;
        }
        return sourceConfigured && lastObservedAt.isBefore(staleBefore) ? State.STALE : State.FRESH;
    }
}
