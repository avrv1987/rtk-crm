package ru.rtk.crm.report;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ManagerWorkReport {
    static final String UNASSIGNED = "Требует назначения";

    private ManagerWorkReport() {
    }

    static List<ReportRow> rows(
            List<ManagerWorkLine> lines,
            Map<UUID, List<String>> products,
            Map<UUID, List<String>> comments,
            OffsetDateTime now
    ) {
        Map<UUID, List<ManagerWorkLine>> byManager = new LinkedHashMap<>();
        lines.stream()
                .sorted(Comparator.comparing((ManagerWorkLine line) -> line.managerName() == null)
                        .thenComparing(line -> line.managerName() == null ? "" : line.managerName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(line -> Objects.toString(line.managerId(), "")))
                .forEach(line -> byManager.computeIfAbsent(line.managerId(), id -> new ArrayList<>()).add(line));
        List<ReportRow> rows = new ArrayList<>();
        for (List<ManagerWorkLine> group : byManager.values()) {
            group.forEach(line -> rows.add(row(line, products.getOrDefault(line.interactionId(), List.of()),
                    comments.getOrDefault(line.interactionId(), List.of()), now)));
            rows.add(total(group, now));
        }
        return rows;
    }

    private static ReportRow row(ManagerWorkLine line, List<String> products, List<String> comments, OffsetDateTime now) {
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
                        overdueDays
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
                        + ": вузов " + organizations + ", работ " + group.size(),
                List.of(),
                new ReportRow.ManagerWork(
                        first.teamName(),
                        null,
                        null,
                        null,
                        group.stream().mapToLong(ManagerWorkLine::transitions).sum(),
                        null,
                        "просрочено " + overdue + ", на этой неделе " + thisWeek + ", без шага или срока " + withoutStep,
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
