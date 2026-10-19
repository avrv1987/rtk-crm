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
    // Разложение состава (разбор §7.3, А08): start, end и change — по сопоставимым потокам, которые идут и на начало,
    // и на конец и имеют значение на начало; endWithoutBase — обучающиеся на конец в идущих потоках без значения на
    // начало (runsWithoutData); startedRuns/endStarted — потоки, начавшиеся внутри периода, и их обучающиеся на конец;
    // endedRuns/startEnded — закончившиеся внутри периода и их обучающиеся на начало. В изменение входят только
    // сопоставимые: плановое окончание потока не выглядит падением, а запуск нового — ростом.
    public record Item(UUID id, String name, long start, long end, long change, int runsWithoutData, long endWithoutBase,
                       int startedRuns, long endStarted, int endedRuns, long startEnded) {
    }
}
