package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LearningTrend(
        OffsetDateTime calculatedAt,
        LocalDate from,
        LocalDate to,
        int days,
        Item total,
        List<Item> teams,
        List<Item> programs,
        List<Item> growing,
        List<Item> falling,
        int runsWithoutData
) {
    // start, end и change — по сопоставимым потокам, у которых есть значение на начало периода; endWithoutBase —
    // обучающиеся на конец в потоках без базы сравнения (runsWithoutData), в изменение они не входят.
    public record Item(UUID id, String name, long start, long end, long change, int runsWithoutData, long endWithoutBase) {
    }
}
