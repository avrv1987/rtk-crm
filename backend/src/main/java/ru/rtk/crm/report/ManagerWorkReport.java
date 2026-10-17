package ru.rtk.crm.report;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import ru.rtk.crm.work.LmsSignalModels.WorkLearning;

final class ManagerWorkReport {
    static final String UNASSIGNED = "Требует назначения";

    private ManagerWorkReport() {
    }

    static List<ReportRow> rows(
            List<ManagerWorkLine> lines,
            Map<UUID, List<String>> products,
            Map<UUID, List<String>> comments,
            Map<UUID, WorkLearning> learning,
            OffsetDateTime now
    ) {
        return byManager(lines).stream()
                .flatMap(group -> group.stream().map(line -> row(line, products.getOrDefault(line.interactionId(), List.of()),
                        comments.getOrDefault(line.interactionId(), List.of()), learning.get(line.interactionId()), now)))
                .toList();
    }

    // Итоги менеджеров не смешиваются со строками данных: иначе сортировка и сумма колонки в Excel считают их дважды.
    static List<ReportRow> totals(List<ManagerWorkLine> lines, OffsetDateTime now) {
        return byManager(lines).stream().map(group -> total(group, now)).toList();
    }

    private static Collection<List<ManagerWorkLine>> byManager(List<ManagerWorkLine> lines) {
        Map<UUID, List<ManagerWorkLine>> byManager = new LinkedHashMap<>();
        lines.stream()
                .sorted(Comparator.comparing((ManagerWorkLine line) -> line.managerName() == null)
                        .thenComparing(line -> line.managerName() == null ? "" : line.managerName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(line -> Objects.toString(line.managerId(), "")))
                .forEach(line -> byManager.computeIfAbsent(line.managerId(), id -> new ArrayList<>()).add(line));
        return byManager.values();
    }

    private static ReportRow row(ManagerWorkLine line, List<String> products, List<String> comments, WorkLearning learning,
                                 OffsetDateTime now) {
        ManagerWorkDue due = line.due(now);
        Long overdueDays = due == ManagerWorkDue.OVERDUE
                ? Math.max(0, Duration.between(line.nextActionAt(), now).toDays())
                : null;
        return build(
                line,
                line.title(),
                line.organizationName(),
                products.stream().map(name -> new ReportRow.Product(null, name)).toList(),
                new ReportRow.ManagerWork(
                        line.teamName(),
                        line.stageAtStart(),
                        line.stageAtEnd(),
                        line.stageTotal() > 0 ? line.stagePosition() + " из " + line.stageTotal() : null,
                        line.transitions(),
                        comments.isEmpty() ? null : String.join("\n", comments),
                        due.label(),
                        overdueDays,
                        learning == null ? null : learning.summary(),
                        learning == null ? null : learning.mismatch()
                )
        );
    }

    private static ReportRow total(List<ManagerWorkLine> group, OffsetDateTime now) {
        ManagerWorkLine first = group.getFirst();
        long overdue = group.stream().filter(line -> line.due(now) == ManagerWorkDue.OVERDUE).count();
        long thisWeek = group.stream().filter(line -> line.due(now) == ManagerWorkDue.THIS_WEEK).count();
        long withoutStep = group.stream().filter(line -> line.due(now) == ManagerWorkDue.NO_NEXT_STEP).count();
        long organizations = group.stream().map(ManagerWorkLine::organizationId).distinct().count();
        return build(
                first,
                null,
                "Итого по менеджеру " + (first.managerName() == null ? "«" + UNASSIGNED + "»" : first.managerName())
                        + ": вузов " + organizations + ", взаимодействий " + group.size(),
                List.of(),
                new ReportRow.ManagerWork(
                        first.teamName(),
                        null,
                        null,
                        null,
                        group.stream().mapToLong(ManagerWorkLine::transitions).sum(),
                        null,
                        "просрочено " + overdue + ", на этой неделе " + thisWeek + ", без шага или срока " + withoutStep,
                        null,
                        null,
                        null
                )
        );
    }

    private static ReportRow build(
            ManagerWorkLine line,
            String title,
            String organizationName,
            List<ReportRow.Product> products,
            ReportRow.ManagerWork managerWork
    ) {
        boolean total = title == null;
        return new ReportRow(
                total ? null : line.interactionId(), title, total ? null : line.organizationId(), organizationName,
                null, total ? null : line.directionName(),
                null, total ? null : line.programName(),
                products,
                total ? null : line.stageName(),
                line.managerId(), line.managerName() == null ? UNASSIGNED : line.managerName(),
                null, null,
                total ? null : line.daysOnStage(),
                total ? null : line.nextAction(), total ? null : line.nextActionAt(),
                null, null, null, null, null,
                null, null, null,
                total ? null : line.marks(),
                List.of(), null, null, null, null, null, null,
                managerWork
        );
    }
}
