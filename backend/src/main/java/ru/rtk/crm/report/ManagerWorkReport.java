package ru.rtk.crm.report;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementRepository.WorkAgreementStatus;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.work.LmsSignalModels.WorkLearning;

final class ManagerWorkReport {
    static final String UNASSIGNED = "Требует назначения";
    static final int DEFAULT_STUCK_DAYS = 30;

    private static final Set<AgreementStatus> AGREEMENT_IN_PROGRESS =
            EnumSet.of(AgreementStatus.REVIEW, AgreementStatus.CORRECTION, AgreementStatus.SIGNING);
    private static final Comparator<ManagerWorkLine> ORDER = Comparator
            .comparing((ManagerWorkLine line) -> Objects.toString(line.teamName(), ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(line -> Objects.toString(line.teamId(), ""))
            .thenComparing(line -> line.managerName() == null)
            .thenComparing(line -> Objects.toString(line.managerName(), ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(line -> Objects.toString(line.managerId(), ""));

    private ManagerWorkReport() {
    }

    // Плитка «Контроля» отбирает строки так же, как экран (controlModel.ts, tileMatches): файл совпадает с тем, что
    // руководитель видел на экране.
    static boolean matches(ManagerWorkLine line, ManagerWorkTile tile, int stuckDays, WorkAgreementStatus agreement,
                           WorkLearning learning, OffsetDateTime now) {
        return switch (tile) {
            case OVERDUE -> line.due(now) == ManagerWorkDue.OVERDUE;
            case THIS_WEEK -> line.due(now) == ManagerWorkDue.THIS_WEEK;
            case NO_NEXT_STEP -> line.due(now) == ManagerWorkDue.NO_NEXT_STEP;
            case ISSUES -> line.withIssues();
            case AGREEMENT -> agreement != null && AGREEMENT_IN_PROGRESS.contains(agreement.status());
            case UNASSIGNED -> line.managerId() == null;
            case STUCK -> active(line) && line.daysOnStage() != null && line.daysOnStage() >= stuckDays;
            case LMS -> learning != null && (learning.signals() > 0 || learning.mismatch() != null && !learning.mismatch().isEmpty());
        };
    }

    private static boolean active(ManagerWorkLine line) {
        InteractionWorkStatus status = line.marks() == null ? null : line.marks().status();
        return status != InteractionWorkStatus.COMPLETED && status != InteractionWorkStatus.PAUSED;
    }

    // agreements == null — колонки договора не выбраны, и договоры не запрашивались.
    static List<ReportRow> rows(
            List<ManagerWorkLine> lines,
            Map<UUID, List<String>> products,
            Map<UUID, List<String>> comments,
            Map<UUID, WorkLearning> learning,
            Map<UUID, WorkAgreementStatus> agreements,
            OffsetDateTime now
    ) {
        return lines.stream().sorted(ORDER)
                .map(line -> row(line, products.getOrDefault(line.interactionId(), List.of()),
                        comments.getOrDefault(line.interactionId(), List.of()), learning.get(line.interactionId()),
                        agreements == null ? null : agreement(agreements.get(line.interactionId()), now), now))
                .toList();
    }

    // Итоги — отдельная таблица с числами в своих колонках: по менеджеру внутри команды («Требует назначения» —
    // своя строка у каждой команды), «Итого по команде» и «Всего». Вузы считаются по уникальным идентификаторам.
    static List<ManagerWorkTotal> totals(List<ManagerWorkLine> lines, OffsetDateTime now) {
        List<ManagerWorkLine> sorted = lines.stream().sorted(ORDER).toList();
        List<ManagerWorkTotal> totals = new ArrayList<>();
        List<ManagerWorkLine> team = new ArrayList<>();
        List<ManagerWorkLine> manager = new ArrayList<>();
        for (ManagerWorkLine line : sorted) {
            if (!team.isEmpty() && !Objects.equals(team.getFirst().teamId(), line.teamId())) {
                totals.add(total(manager, managerName(manager.getFirst()), now));
                totals.add(total(team, "Итого по команде", now));
                team.clear();
                manager.clear();
            } else if (!manager.isEmpty() && !Objects.equals(manager.getFirst().managerId(), line.managerId())) {
                totals.add(total(manager, managerName(manager.getFirst()), now));
                manager.clear();
            }
            team.add(line);
            manager.add(line);
        }
        if (!team.isEmpty()) {
            totals.add(total(manager, managerName(manager.getFirst()), now));
            totals.add(total(team, "Итого по команде", now));
            ManagerWorkTotal all = total(sorted, "Всего", now);
            totals.add(new ManagerWorkTotal("Все команды", all.manager(), all.organizations(), all.works(), all.overdue(),
                    all.thisWeek(), all.noNextStep(), all.transitions()));
        }
        return totals;
    }

    private static String managerName(ManagerWorkLine line) {
        return line.managerName() == null ? UNASSIGNED : line.managerName();
    }

    private static ManagerWorkTotal total(List<ManagerWorkLine> group, String manager, OffsetDateTime now) {
        return new ManagerWorkTotal(
                group.getFirst().teamName(),
                manager,
                group.stream().map(ManagerWorkLine::organizationId).distinct().count(),
                group.size(),
                group.stream().filter(line -> line.due(now) == ManagerWorkDue.OVERDUE).count(),
                group.stream().filter(line -> line.due(now) == ManagerWorkDue.THIS_WEEK).count(),
                group.stream().filter(line -> line.due(now) == ManagerWorkDue.NO_NEXT_STEP).count(),
                group.stream().mapToLong(ManagerWorkLine::transitions).sum()
        );
    }

    private static ReportRow row(ManagerWorkLine line, List<String> products, List<String> comments, WorkLearning learning,
                                 ReportRow.AgreementLine agreement, OffsetDateTime now) {
        ManagerWorkDue due = line.due(now);
        Long overdueDays = due == ManagerWorkDue.OVERDUE
                ? Math.max(0, Duration.between(line.nextActionAt(), now).toDays())
                : null;
        return new ReportRow(
                line.interactionId(), line.title(), line.organizationId(), line.organizationName(),
                null, line.directionName(),
                null, line.programName(),
                products.stream().map(name -> new ReportRow.Product(null, name)).toList(),
                line.stageName(),
                line.managerId(), managerName(line),
                null, null,
                line.daysOnStage(),
                line.nextAction(), line.nextActionAt(),
                null, null, null, null, null,
                null, null, null,
                line.marks(),
                List.of(), null, null, agreement, null, null, null,
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

    // Колонки «Договор» и «Статус договора» показывают последний связанный с взаимодействием договор, как колонка
    // «Договор» на экране «Контроля».
    // «Срок истёк» дописывается к статусу так же, как отметка на экране: статус по сроку не меняется.
    private static ReportRow.AgreementLine agreement(WorkAgreementStatus agreement, OffsetDateTime now) {
        String status = agreement == null ? "Нет" : agreement.status().title()
                + (agreement.status().expired(agreement.validUntil(), now.atZoneSameInstant(ReportRequest.ZONE).toLocalDate())
                ? " · срок истёк" : "");
        return new ReportRow.AgreementLine(null, null,
                agreement == null ? null : "№ " + agreement.number(),
                status,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
