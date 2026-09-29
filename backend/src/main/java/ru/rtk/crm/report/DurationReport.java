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
    static final String WHOLE_CYCLE = "Весь цикл";

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);
    private static final Comparator<Map.Entry<Key, Stats>> ORDER = Comparator
            .comparing((Map.Entry<Key, Stats> entry) -> entry.getKey().teamName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.getKey().teamId())
            .thenComparing(entry -> !entry.getKey().allPrograms())
            .thenComparing(entry -> entry.getKey().programName() == null)
            .thenComparing(entry -> Objects.requireNonNullElse(entry.getKey().programName(), ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.getKey().stageName() != null)
            .thenComparingInt(entry -> entry.getValue().stageOrder)
            .thenComparing(entry -> Objects.requireNonNullElse(entry.getKey().stageName(), ""));

    private DurationReport() {
    }

    static List<ReportRow> rows(List<StageEntry> entries, List<String> stages, OffsetDateTime fromAt, OffsetDateTime cutoff) {
        Map<Key, Stats> groups = new HashMap<>();
        int start = 0;
        while (start < entries.size()) {
            int end = start + 1;
            while (end < entries.size() && entries.get(end).interactionId().equals(entries.get(start).interactionId())) {
                end++;
            }
            addInteraction(entries.subList(start, end), stages, fromAt, cutoff, groups);
            start = end;
        }
        return groups.entrySet().stream().sorted(ORDER).map(DurationReport::row).toList();
    }

    private static void addInteraction(
            List<StageEntry> visits,
            List<String> stages,
            OffsetDateTime fromAt,
            OffsetDateTime cutoff,
            Map<Key, Stats> groups
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
            for (Key key : keys(first, visit.stageName())) {
                Stats stats = groups.computeIfAbsent(key, ignored -> new Stats());
                stats.stageOrder = Math.min(stats.stageOrder, order);
                add(stats, enteredAt, leftAt, fromAt, cutoff);
            }
        }
        for (Key key : keys(first, null)) {
            add(groups.computeIfAbsent(key, ignored -> new Stats()), first.createdAt(), completedAt, fromAt, cutoff);
        }
    }

    private static void add(Stats stats, OffsetDateTime startedAt, OffsetDateTime endedAt, OffsetDateTime fromAt, OffsetDateTime cutoff) {
        if (endedAt != null && endedAt.isBefore(cutoff)) {
            if (fromAt == null || !endedAt.isBefore(fromAt)) {
                long seconds = Duration.between(startedAt, endedAt).toSeconds();
                stats.completed++;
                stats.completedSeconds += seconds;
                stats.completedMaxSeconds = Math.max(stats.completedMaxSeconds, seconds);
            }
            return;
        }
        long seconds = Duration.between(startedAt, cutoff).toSeconds();
        stats.current++;
        stats.currentMaxSeconds = Math.max(stats.currentMaxSeconds, seconds);
    }

    private static List<Key> keys(StageEntry interaction, String stageName) {
        return List.of(
                new Key(interaction.teamId(), interaction.teamName(), false, interaction.programId(), interaction.programName(), stageName),
                new Key(interaction.teamId(), interaction.teamName(), true, null, null, stageName)
        );
    }

    private static ReportRow row(Map.Entry<Key, Stats> entry) {
        Key key = entry.getKey();
        Stats stats = entry.getValue();
        ReportRow.StageDuration duration = new ReportRow.StageDuration(
                key.teamName(),
                stats.completed,
                stats.completed == 0 ? null : days(stats.completedSeconds / stats.completed),
                stats.completed == 0 ? null : days(stats.completedMaxSeconds),
                stats.current,
                stats.current == 0 ? null : days(stats.currentMaxSeconds)
        );
        return new ReportRow(
                null, null, null, null, null, null,
                key.programId(),
                key.allPrograms() ? ALL_PROGRAMS : key.programName(),
                List.of(),
                key.stageName() == null ? WHOLE_CYCLE : key.stageName(),
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

    private static BigDecimal days(long seconds) {
        return BigDecimal.valueOf(seconds).divide(SECONDS_PER_DAY, 1, RoundingMode.HALF_UP);
    }

    private record Key(UUID teamId, String teamName, boolean allPrograms, UUID programId, String programName, String stageName) {
    }

    private static final class Stats {
        private int stageOrder = Integer.MAX_VALUE;
        private long completed;
        private long completedSeconds;
        private long completedMaxSeconds;
        private long current;
        private long currentMaxSeconds;
    }
}
