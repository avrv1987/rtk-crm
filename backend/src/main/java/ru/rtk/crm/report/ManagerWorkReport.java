package ru.rtk.crm.report;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.ApprovalColor;
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
            // На этапе договора, пока договор на согласовании, действуют пороги согласования (AgreementApproval).
            case STUCK -> active(line) && line.daysOnStage() != null && line.daysOnStage() >= stuckDays
                    && (agreement == null || !agreement.stageUnderApproval());
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
    // своя строка у каждой команды), «Итого по команде» и «Всего». Вузы считаются по уникальным идентификаторам
    // вместе с вузами без идущей работы (idle), как на экране «Контроля»: вуз без работы входит в строку своего КАМ,
    // а вуз без КАМ — в строку «Требует назначения» своей команды.
    static List<ManagerWorkTotal> totals(List<ManagerWorkLine> lines, List<ManagerWorkOrganization> idle, OffsetDateTime now) {
        Map<TotalKey, TotalGroup> groups = new TreeMap<>(TOTAL_ORDER);
        lines.forEach(line -> groups.computeIfAbsent(
                new TotalKey(line.teamId(), line.teamName(), line.managerId(), managerName(line)), key -> new TotalGroup()
        ).lines.add(line));
        idle.forEach(organization -> groups.computeIfAbsent(
                new TotalKey(organization.teamId(), organization.teamName(), organization.managerId(),
                        organization.managerId() == null ? UNASSIGNED : organization.managerName()),
                key -> new TotalGroup()
        ).organizations.add(organization.id()));
        List<ManagerWorkTotal> totals = new ArrayList<>();
        TotalGroup all = new TotalGroup();
        TotalGroup team = new TotalGroup();
        TotalKey teamKey = null;
        for (Map.Entry<TotalKey, TotalGroup> entry : groups.entrySet()) {
            if (teamKey != null && !Objects.equals(teamKey.teamId(), entry.getKey().teamId())) {
                totals.add(team.total(teamKey.teamName(), "Итого по команде", now));
                team = new TotalGroup();
            }
            teamKey = entry.getKey();
            totals.add(entry.getValue().total(teamKey.teamName(), teamKey.managerName(), now));
            team.add(entry.getValue());
            all.add(entry.getValue());
        }
        if (teamKey != null) {
            totals.add(team.total(teamKey.teamName(), "Итого по команде", now));
            totals.add(all.total("Все команды", "Всего", now));
        }
        return totals;
    }

    private record TotalKey(UUID teamId, String teamName, UUID managerId, String managerName) {
    }

    private static final Comparator<TotalKey> TOTAL_ORDER = Comparator
            .comparing((TotalKey key) -> Objects.toString(key.teamName(), ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(key -> Objects.toString(key.teamId(), ""))
            .thenComparing(key -> key.managerId() == null)
            .thenComparing(key -> Objects.toString(key.managerName(), ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(key -> Objects.toString(key.managerId(), ""));

    private static final class TotalGroup {
        private final List<ManagerWorkLine> lines = new ArrayList<>();
        private final Set<UUID> organizations = new HashSet<>();

        private void add(TotalGroup other) {
            lines.addAll(other.lines);
            organizations.addAll(other.organizations);
        }

        private ManagerWorkTotal total(String team, String manager, OffsetDateTime now) {
            Set<UUID> all = new HashSet<>(organizations);
            lines.forEach(line -> all.add(line.organizationId()));
            return new ManagerWorkTotal(
                    team,
                    manager,
                    all.size(),
                    lines.size(),
                    lines.stream().filter(line -> line.due(now) == ManagerWorkDue.OVERDUE).count(),
                    lines.stream().filter(line -> line.due(now) == ManagerWorkDue.THIS_WEEK).count(),
                    lines.stream().filter(line -> line.due(now) == ManagerWorkDue.NO_NEXT_STEP).count(),
                    lines.stream().mapToLong(ManagerWorkLine::transitions).sum()
            );
        }
    }

    private static String managerName(ManagerWorkLine line) {
        return line.managerName() == null ? UNASSIGNED : line.managerName();
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
    // «Срок истёк» и затянувшееся согласование дописываются к статусу так же, как отметки на экране.
    private static ReportRow.AgreementLine agreement(WorkAgreementStatus agreement, OffsetDateTime now) {
        String status = agreement == null ? "Нет" : agreement.status().title()
                + (agreement.status().expired(agreement.validUntil(), now.atZoneSameInstant(ReportRequest.ZONE).toLocalDate())
                ? " · срок истёк" : "")
                + (agreement != null && agreement.approval() != null && agreement.approval().delayed()
                ? " · согласование: " + (agreement.approval().color() == ApprovalColor.RED ? "красный" : "жёлтый") : "");
        return new ReportRow.AgreementLine(null, null,
                agreement == null ? null : "№ " + agreement.number(),
                status,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
