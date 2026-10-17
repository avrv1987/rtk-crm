package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementRepository;
import ru.rtk.crm.agreement.AgreementRepository.WorkAgreementStatus;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.interaction.InteractionMarks;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.report.ManagerWorkLine;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.work.LmsSignalModels.WorkLearning;
import ru.rtk.crm.work.WorkControlModels.Control;
import ru.rtk.crm.work.WorkControlModels.ManagerBlock;
import ru.rtk.crm.work.WorkControlModels.TeamBlock;
import ru.rtk.crm.work.WorkControlModels.Totals;
import ru.rtk.crm.work.WorkControlModels.WorkItem;

@Service
public class WorkControlService {
    private static final UUID UNASSIGNED_KEY = new UUID(0, 0);

    private final ReportRepository reportRepository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final AgreementRepository agreementRepository;
    private final LmsWorkLearningService lmsWorkLearningService;

    public WorkControlService(
            ReportRepository reportRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            AgreementRepository agreementRepository,
            LmsWorkLearningService lmsWorkLearningService
    ) {
        this.reportRepository = reportRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.agreementRepository = agreementRepository;
        this.lmsWorkLearningService = lmsWorkLearningService;
    }

    @Transactional(readOnly = true)
    public Control control(
            CrmProfile profile,
            ReportFilters requestedFilters,
            LocalDate from,
            LocalDate to
    ) {
        boolean leader = profile.role() == UserRole.LEADER && profile.teamId() != null;
        if (!leader && profile.role() != UserRole.MANAGEMENT) {
            throw new WorkAccessDeniedException();
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        ReportFilters filters = requestedFilters.normalized();
        OffsetDateTime now = OffsetDateTime.now();
        List<ManagerWorkLine> lines = reportRepository.findManagerWork(profile, filters, from, to, now);
        Map<UUID, List<String>> products = reportRepository.findManagerWorkProducts(
                lines.stream().map(ManagerWorkLine::interactionId).toList()
        );
        Map<UUID, WorkAgreementStatus> agreements = agreementRepository.findWorkStatuses(
                lines.stream().map(ManagerWorkLine::interactionId).toList()
        );
        Map<UUID, WorkLearning> learning = lmsWorkLearningService.byWork(
                profile, lines.stream().map(ManagerWorkLine::interactionId).toList()
        );
        Map<UUID, List<ManagerWorkLine>> byTeam = lines.stream().collect(Collectors.groupingBy(
                ManagerWorkLine::teamId, LinkedHashMap::new, Collectors.toList()
        ));
        // Менеджеры команды без работ показываются, только когда руководитель смотрит всё без отборов и периода:
        // при любом отборе пустая строка менеджера выглядела бы как «у него нет работ», хотя их скрыл отбор.
        List<ManagerBlock> idle = leader && unrestricted(filters, from, to)
                ? idleManagers(profile, lines, now)
                : List.of();
        List<TeamBlock> teams = new ArrayList<>();
        byTeam.values().forEach(teamLines -> teams.add(team(teamLines, products, agreements, learning, now, idle)));
        if (teams.isEmpty() && !idle.isEmpty()) {
            teams.add(new TeamBlock(profile.teamId(), null, totals(List.of(), now), idle));
        }
        teams.sort(Comparator.comparing(team -> Objects.toString(team.teamName(), ""), String.CASE_INSENSITIVE_ORDER));
        return new Control(now, from, to, !leader, teams, totals(lines, now));
    }

    private List<ManagerBlock> idleManagers(CrmProfile leader, List<ManagerWorkLine> lines, OffsetDateTime now) {
        return organizationAssignmentRepository.findCandidates(leader.teamId(), leader.id()).stream()
                .filter(candidate -> !candidate.id().equals(leader.id()))
                .filter(candidate -> lines.stream().noneMatch(line -> candidate.id().equals(line.managerId())))
                .map(candidate -> new ManagerBlock(candidate.id(), candidate.displayName(), totals(List.of(), now), List.of()))
                .toList();
    }

    private static boolean unrestricted(ReportFilters filters, LocalDate from, LocalDate to) {
        return filters.equals(ReportFilters.none().normalized()) && from == null && to == null;
    }

    private TeamBlock team(
            List<ManagerWorkLine> teamLines,
            Map<UUID, List<String>> products,
            Map<UUID, WorkAgreementStatus> agreements,
            Map<UUID, WorkLearning> learning,
            OffsetDateTime now,
            List<ManagerBlock> idle
    ) {
        // Работы вузов без действующего КАМ собираются в отдельный блок «не назначен» и идут после менеджеров.
        Map<UUID, List<ManagerWorkLine>> byManager = teamLines.stream().collect(Collectors.groupingBy(
                line -> Objects.requireNonNullElse(line.managerId(), UNASSIGNED_KEY), LinkedHashMap::new, Collectors.toList()
        ));
        List<ManagerBlock> managers = new ArrayList<>(idle);
        byManager.values().forEach(managerLines -> managers.add(manager(managerLines, products, agreements, learning, now)));
        managers.sort(Comparator.comparing((ManagerBlock block) -> block.managerId() == null)
                .thenComparing(block -> Objects.toString(block.managerName(), ""), String.CASE_INSENSITIVE_ORDER));
        ManagerWorkLine first = teamLines.getFirst();
        return new TeamBlock(first.teamId(), first.teamName(), totals(teamLines, now), managers);
    }

    private ManagerBlock manager(
            List<ManagerWorkLine> managerLines,
            Map<UUID, List<String>> products,
            Map<UUID, WorkAgreementStatus> agreements,
            Map<UUID, WorkLearning> learning,
            OffsetDateTime now
    ) {
        ManagerWorkLine first = managerLines.getFirst();
        return new ManagerBlock(
                first.managerId(),
                first.managerName(),
                totals(managerLines, now),
                managerLines.stream()
                        .map(line -> item(line, products.getOrDefault(line.interactionId(), List.of()),
                                agreements.get(line.interactionId()), learning.get(line.interactionId()), now))
                        .toList()
        );
    }

    private static WorkItem item(ManagerWorkLine line, List<String> products, WorkAgreementStatus agreement,
                                 WorkLearning learning, OffsetDateTime now) {
        InteractionMarks marks = line.marks();
        String issues = marks == null
                ? ""
                : Stream.of(marks.problems(), marks.risks()).filter(Objects::nonNull).collect(Collectors.joining("; "));
        return new WorkItem(
                line.interactionId(),
                line.organizationId(),
                line.organizationName(),
                line.title(),
                line.directionName(),
                line.programName(),
                products,
                line.stageName(),
                line.stagePosition(),
                line.stageTotal(),
                line.daysOnStage(),
                line.stageAtStart(),
                line.nextAction(),
                line.nextActionAt(),
                line.due(now),
                marks == null ? null : marks.status(),
                marks == null ? 0 : marks.problemCount(),
                marks == null ? 0 : marks.riskCount(),
                issues.isEmpty() ? null : issues,
                line.transitions(),
                line.created(),
                line.completed(),
                agreement == null ? null : agreement.status(),
                agreement == null ? null : agreement.number(),
                learning
        );
    }

    // Каждая работа попадает ровно в одну из групп срока (просрочено, на этой неделе, без шага, в порядке),
    // поэтому сумма групп равна числу работ; вузы считаются по уникальным идентификаторам.
    private static Totals totals(List<ManagerWorkLine> lines, OffsetDateTime now) {
        return new Totals(
                lines.stream().map(ManagerWorkLine::organizationId).distinct().count(),
                lines.size(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.OVERDUE).count(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.THIS_WEEK).count(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.NO_NEXT_STEP).count(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.IN_ORDER).count(),
                lines.stream().filter(ManagerWorkLine::withIssues).count(),
                lines.stream().filter(line -> line.transitions() > 0).count(),
                lines.stream().filter(ManagerWorkLine::created).count(),
                lines.stream().filter(ManagerWorkLine::completed).count()
        );
    }
}
