package ru.rtk.crm.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import ru.rtk.crm.report.ReportRepository.StageEntry;

final class DurationReport {
    static final String ALL_PROGRAMS = "Все программы";
    static final String WHOLE_CYCLE = "Время до входа в последний этап";

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);
    private static final Comparator<Map.Entry<Key, Stats>> ORDER = Comparator
            .comparing((Map.Entry<Key, Stats> entry) -> entry.getKey().teamName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.getKey().teamId())
            .thenComparing(entry -> !entry.getKey().allPrograms())
            .thenComparing(entry -> entry.getKey().programName() == null)
            .thenComparing(entry -> Objects.requireNonNullElse(entry.getKey().programName(), ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> Objects.requireNonNullElse(entry.getKey().routeName(), ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.getKey().stageName() != null)
            .thenComparingInt(entry -> entry.getValue().stageOrder)
            .thenComparing(entry -> Objects.requireNonNullElse(entry.getKey().stageName(), ""));

    private DurationReport() {
    }

    // Несколько маршрутов в выборке — этапы и весь цикл считаются по каждому шаблону отдельно: одноимённые этапы разных
    // маршрутов означают разную работу, а порядок этапов берётся из маршрута самой работы.
    static List<ReportRow> rows(List<StageEntry> entries, List<String> stages, OffsetDateTime fromAt, OffsetDateTime cutoff) {
        Map<Key, Stats> groups = new HashMap<>();
        boolean byRoute = entries.stream().map(StageEntry::templateId).distinct().count() > 1;
        int start = 0;
        while (start < entries.size()) {
            int end = start + 1;
            while (end < entries.size() && entries.get(end).interactionId().equals(entries.get(start).interactionId())) {
                end++;
            }
            addInteraction(entries.subList(start, end), stages, fromAt, cutoff, groups, byRoute);
            start = end;
        }
        return groups.entrySet().stream().sorted(ORDER).map(DurationReport::row).toList();
    }

    private static void addInteraction(
            List<StageEntry> visits,
            List<String> stages,
            OffsetDateTime fromAt,
            OffsetDateTime cutoff,
            Map<Key, Stats> groups,
            boolean byRoute
    ) {
        StageEntry first = visits.getFirst();
        if (!first.createdAt().isBefore(cutoff)) {
            return;
        }
        OffsetDateTime completedAt = null;
        for (int index = 0; index < visits.size(); index++) {
            StageEntry visit = visits.get(index);
            OffsetDateTime enteredAt = visit.occurredAt();
            if (!enteredAt.isBefore(cutoff)) {
                break;
            }
            if (visit.finalStage() && completedAt == null) {
                completedAt = enteredAt;
            }
            if (!stages.isEmpty() && !stages.contains(visit.stageName())) {
                continue;
            }
            OffsetDateTime leftAt = index + 1 < visits.size() ? visits.get(index + 1).occurredAt() : null;
            int order = visit.stageOrder() == null ? Integer.MAX_VALUE : visit.stageOrder();
            for (Key key : keys(first, visit.stageName(), byRoute)) {
                Stats stats = groups.computeIfAbsent(key, ignored -> new Stats());
                stats.stageOrder = Math.min(stats.stageOrder, order);
                add(stats, enteredAt, leftAt, fromAt, cutoff);
            }
        }
        for (Key key : keys(first, null, byRoute)) {
            add(groups.computeIfAbsent(key, ignored -> new Stats()), first.createdAt(), completedAt, fromAt, cutoff);
        }
    }

    private static void add(Stats stats, OffsetDateTime startedAt, OffsetDateTime endedAt, OffsetDateTime fromAt, OffsetDateTime cutoff) {
        if (endedAt != null && endedAt.isBefore(cutoff)) {
            if (fromAt == null || !endedAt.isBefore(fromAt)) {
                long seconds = Duration.between(startedAt, endedAt).toSeconds();
                stats.completedSeconds.add(seconds);
                stats.completedSum += seconds;
            }
            return;
        }
        long seconds = Duration.between(startedAt, cutoff).toSeconds();
        stats.current++;
        stats.currentMaxSeconds = Math.max(stats.currentMaxSeconds, seconds);
    }

    private static List<Key> keys(StageEntry interaction, String stageName, boolean byRoute) {
        String route = byRoute ? Objects.requireNonNullElse(interaction.templateName(), "Без шаблона") : null;
        UUID routeId = byRoute ? interaction.templateId() : null;
        return List.of(
                new Key(interaction.teamId(), interaction.teamName(), false, interaction.programId(), interaction.programName(),
                        routeId, route, stageName),
                new Key(interaction.teamId(), interaction.teamName(), true, null, null, routeId, route, stageName)
        );
    }

    private static ReportRow row(Map.Entry<Key, Stats> entry) {
        Key key = entry.getKey();
        Stats stats = entry.getValue();
        List<Long> completed = stats.completedSeconds.stream().sorted().toList();
        int count = completed.size();
        ReportRow.StageDuration duration = new ReportRow.StageDuration(
                key.teamName(),
                count,
                count == 0 ? null : days(stats.completedSum / count),
                count == 0 ? null : days(completed.getLast()),
                stats.current,
                stats.current == 0 ? null : days(stats.currentMaxSeconds),
                count == 0 ? null : median(completed),
                count == 0 ? null : days(nearestRank(completed, 90))
        );
        return new ReportRow(
                null, null, null, null, null, null,
                key.programId(),
                key.allPrograms() ? ALL_PROGRAMS : key.programName(),
                List.of(),
                (key.routeName() == null ? "" : key.routeName() + ": ") + (key.stageName() == null ? WHOLE_CYCLE : key.stageName()),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null,
                List.of(),
                duration,
                null,
                null,
                null,
                null,
                null
        );
    }

    // Медиана: при чётном числе прохождений — среднее двух центральных.
    private static BigDecimal median(List<Long> sorted) {
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? days(sorted.get(middle))
                : days((sorted.get(middle - 1) + sorted.get(middle)) / 2);
    }

    // Процентиль методом ближайшего ранга: всегда одно из наблюдённых значений, без интерполяции. При малом n он
    // совпадает с максимумом — поэтому рядом всегда показано число прохождений.
    static long nearestRank(List<Long> sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static BigDecimal days(long seconds) {
        return BigDecimal.valueOf(seconds).divide(SECONDS_PER_DAY, 1, RoundingMode.HALF_UP);
    }

    private record Key(UUID teamId, String teamName, boolean allPrograms, UUID programId, String programName, UUID routeId,
                       String routeName, String stageName) {

    }

    private static final class Stats {
        private int stageOrder = Integer.MAX_VALUE;
        private final List<Long> completedSeconds = new ArrayList<>();
        private long completedSum;
        private long current;
        private long currentMaxSeconds;
    }
}
