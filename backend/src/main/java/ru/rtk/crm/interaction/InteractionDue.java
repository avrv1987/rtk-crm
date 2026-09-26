package ru.rtk.crm.interaction;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;

enum InteractionDue {
    OVERDUE,
    THIS_WEEK,
    NO_NEXT_STEP;

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    static InteractionDue from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(due -> due.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("due", "Такой отбор по сроку не поддерживается"));
    }

    static OffsetDateTime weekStart(OffsetDateTime now) {
        return now.atZoneSameInstant(ZONE)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZONE)
                .toOffsetDateTime();
    }
}
