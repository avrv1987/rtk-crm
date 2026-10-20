package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementRepository;
import ru.rtk.crm.agreement.AgreementModels.RegistryRow;
import ru.rtk.crm.agreement.AgreementRepository.WorkAgreementStatus;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionMarks;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ManagerAt;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.report.ManagerWorkLine;
import ru.rtk.crm.report.ManagerWorkOrganization;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.report.ReportRepository.ProductRef;
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
    private final OrganizationRepository organizationRepository;

    public WorkControlService(
            ReportRepository reportRepository,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            AgreementRepository agreementRepository,
            LmsWorkLearningService lmsWorkLearningService,
            OrganizationRepository organizationRepository
    ) {
        this.reportRepository = reportRepository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.agreementRepository = agreementRepository;
        this.lmsWorkLearningService = lmsWorkLearningService;
        this.organizationRepository = organizationRepository;
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
        // Режим «За период» показывает КАМ на конец периода по тому же правилу, что отчёт «Работа менеджеров»; без
        // периода режим всегда «Сейчас». Область видимости остаётся текущей.
        boolean managerAtPeriodEnd = filters.managerAt() == ManagerAt.PERIOD_END && to != null;
        List<ManagerWorkLine> lines = reportRepository.findManagerWork(profile, filters, from, to, now, managerAtPeriodEnd);
        Map<UUID, List<ProductRef>> products = reportRepository.findManagerWorkProductRefs(
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
        // Вузы без идущего взаимодействия («Без работы» у КАМ и вузы без КАМ) входят в числа по общему правилу
        // ManagerWorkOrganization.counted: тогда число вузов совпадает с разделом «Вузы».
        List<ManagerWorkOrganization> withoutWork = ManagerWorkOrganization.counted(filters, from, to)
                ? reportRepository.findOrganizationsWithoutActiveWork(profile).stream()
                        .filter(organization -> organization.selected(filters))
                        .toList()
                : null;
        List<ManagerWorkOrganization> without = withoutWork == null ? List.of() : withoutWork;
        Map<UUID, List<ManagerWorkOrganization>> withoutByTeam = without.stream().collect(Collectors.groupingBy(
                ManagerWorkOrganization::teamId, LinkedHashMap::new, Collectors.toList()
        ));
        withoutByTeam.keySet().forEach(teamId -> byTeam.putIfAbsent(teamId, List.of()));
        // Менеджеры команды без работ показываются, только когда руководитель смотрит всё без отборов и периода:
        // при любом отборе пустая строка менеджера выглядела бы как «у него нет работ», хотя их скрыл отбор.
        List<CandidateManager> idle = leader && unrestricted(filters, from, to)
                ? idleManagers(profile, lines)
                : List.of();
        List<TeamBlock> teams = new ArrayList<>();
        byTeam.forEach((teamId, teamLines) -> teams.add(team(teamId, teamLines, withoutByTeam.getOrDefault(teamId, List.of()),
                products, agreements, learning, now, idle)));
        if (teams.isEmpty() && !idle.isEmpty()) {
            teams.add(new TeamBlock(profile.teamId(), null, totals(List.of(), List.of(), now),
                    idle.stream().map(candidate -> idleBlock(candidate, List.of(), now)).toList()));
        }
        teams.sort(Comparator.comparing(team -> Objects.toString(team.teamName(), ""), String.CASE_INSENSITIVE_ORDER));
        List<ManagerWorkOrganization> idleOrganizations = withoutWork == null
                ? null
                : without.stream().filter(organization -> organization.managerId() != null).toList();
        // Маршруты строк нужны матрице «программа × этап»: столбцы — этапы шаблона по порядку, включая пустые.
        Set<UUID> routeIds = new LinkedHashSet<>();
        lines.forEach(line -> routeIds.add(line.routeId()));
        return new Control(now, from, to, !leader, teams, totals(lines, without, now), idleOrganizations,
                agreementsInProgress(profile, filters, from, to, lines, without, now),
                reportRepository.findStageRoutes(profile, routeIds));
    }

    // Плитка «Договор на согласовании» считает договоры по правилу реестра (статусы, область), а не только привязанные к
    // взаимодействию: иначе договор, который КАМ не привязал, не виден на главном экране. Без отборов и периода числа
    // совпадают с реестром; при отборе остаются договоры вузов, попавших в строки или в «Без работы».
    private List<RegistryRow> agreementsInProgress(CrmProfile profile, ReportFilters filters, LocalDate from, LocalDate to,
                                                   List<ManagerWorkLine> lines, List<ManagerWorkOrganization> without,
                                                   OffsetDateTime now) {
        List<RegistryRow> all = organizationRepository.visibilityScope(profile)
                .map(scope -> agreementRepository.findInProgress(scope, now.atZoneSameInstant(WorkProperties.ZONE).toLocalDate()))
                .orElse(List.of());
        if (unrestricted(filters, from, to)) {
            return all;
        }
        Set<UUID> shown = Stream.concat(lines.stream().map(ManagerWorkLine::organizationId), without.stream().map(ManagerWorkOrganization::id))
                .collect(Collectors.toSet());
        return all.stream().filter(row -> shown.contains(row.organizationId())).toList();
    }

    private record CandidateManager(UUID id, String displayName) {
    }

    private List<CandidateManager> idleManagers(CrmProfile leader, List<ManagerWorkLine> lines) {
        return organizationAssignmentRepository.findCandidates(leader.teamId(), leader.id()).stream()
                .filter(candidate -> !candidate.id().equals(leader.id()))
                .filter(candidate -> lines.stream().noneMatch(line -> candidate.id().equals(line.managerId())))
                .map(candidate -> new CandidateManager(candidate.id(), candidate.displayName()))
                .toList();
    }

    private static boolean unrestricted(ReportFilters filters, LocalDate from, LocalDate to) {
        return filters.withManagerAt(null).equals(ReportFilters.none().normalized()) && from == null && to == null;
    }

    private TeamBlock team(
            UUID teamId,
            List<ManagerWorkLine> teamLines,
            List<ManagerWorkOrganization> teamWithout,
            Map<UUID, List<ProductRef>> products,
            Map<UUID, WorkAgreementStatus> agreements,
            Map<UUID, WorkLearning> learning,
            OffsetDateTime now,
            List<CandidateManager> idle
    ) {
        // Работы вузов без действующего КАМ собираются в отдельный блок «не назначен» и идут после менеджеров.
        Map<UUID, List<ManagerWorkLine>> byManager = teamLines.stream().collect(Collectors.groupingBy(
                line -> Objects.requireNonNullElse(line.managerId(), UNASSIGNED_KEY), LinkedHashMap::new, Collectors.toList()
        ));
        // Вузы «Без работы» входят в числа своего КАМ, вузы без КАМ — в блок «не назначен», как в выгрузке;
        // КАМ, у которого есть только такие вузы, тоже получает строку.
        Map<UUID, List<ManagerWorkOrganization>> idleByManager = teamWithout.stream()
                .collect(Collectors.groupingBy(organization -> Objects.requireNonNullElse(organization.managerId(), UNASSIGNED_KEY),
                        LinkedHashMap::new, Collectors.toList()));
        List<ManagerBlock> managers = new ArrayList<>();
        byManager.forEach((key, managerLines) -> managers.add(manager(managerLines,
                idleByManager.getOrDefault(key, List.of()), products, agreements, learning, now)));
        idleByManager.forEach((key, organizations) -> {
            if (!byManager.containsKey(key)) {
                UUID managerId = UNASSIGNED_KEY.equals(key) ? null : key;
                managers.add(idleBlock(new CandidateManager(managerId, organizations.getFirst().managerName()), organizations, now));
            }
        });
        idle.stream()
                .filter(candidate -> !byManager.containsKey(candidate.id()) && !idleByManager.containsKey(candidate.id()))
                .forEach(candidate -> managers.add(idleBlock(candidate, List.of(), now)));
        managers.sort(Comparator.comparing((ManagerBlock block) -> block.managerId() == null)
                .thenComparing(block -> Objects.toString(block.managerName(), ""), String.CASE_INSENSITIVE_ORDER));
        String teamName = teamLines.isEmpty() ? teamWithout.getFirst().teamName() : teamLines.getFirst().teamName();
        return new TeamBlock(teamId, teamName, totals(teamLines, teamWithout, now), managers);
    }

    private static ManagerBlock idleBlock(CandidateManager manager, List<ManagerWorkOrganization> idle, OffsetDateTime now) {
        return new ManagerBlock(manager.id(), manager.displayName(), totals(List.of(), idle, now), List.of());
    }

    private ManagerBlock manager(
            List<ManagerWorkLine> managerLines,
            List<ManagerWorkOrganization> idle,
            Map<UUID, List<ProductRef>> products,
            Map<UUID, WorkAgreementStatus> agreements,
            Map<UUID, WorkLearning> learning,
            OffsetDateTime now
    ) {
        ManagerWorkLine first = managerLines.getFirst();
        return new ManagerBlock(
                first.managerId(),
                first.managerName(),
                totals(managerLines, idle, now),
                managerLines.stream()
                        .map(line -> item(line, products.getOrDefault(line.interactionId(), List.of()),
                                agreements.get(line.interactionId()), learning.get(line.interactionId()), now))
                        .toList()
        );
    }

    private static WorkItem item(ManagerWorkLine line, List<ProductRef> products, WorkAgreementStatus agreement,
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
                products.stream().map(ProductRef::name).toList(),
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
                agreement == null ? null : agreement.validUntil(),
                learning,
                agreement == null ? null : agreement.approval(),
                agreement != null && agreement.stageUnderApproval(),
                line.programId(),
                products.stream().map(ProductRef::id).toList(),
                line.kindId(),
                line.routeId(),
                line.stageAtEnd() == null ? line.stageName() : line.stageAtEnd(),
                line.missingDocuments(),
                line.stageNormDays()
        );
    }

    // Каждая работа попадает ровно в одну из групп срока (просрочено, на этой неделе, без шага, в порядке),
    // поэтому сумма групп равна числу работ; вузы считаются по уникальным идентификаторам вместе с вузами без работы.
    private static Totals totals(List<ManagerWorkLine> lines, List<ManagerWorkOrganization> without, OffsetDateTime now) {
        return new Totals(
                Stream.concat(lines.stream().map(ManagerWorkLine::organizationId), without.stream().map(ManagerWorkOrganization::id))
                        .distinct().count(),
                lines.size(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.OVERDUE).count(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.THIS_WEEK).count(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.NO_NEXT_STEP).count(),
                lines.stream().filter(line -> line.due(now) == ManagerWorkDue.IN_ORDER).count(),
                lines.stream().filter(ManagerWorkLine::withIssues).count(),
                lines.stream().filter(line -> line.transitions() > 0).count(),
                lines.stream().filter(ManagerWorkLine::created).count(),
                lines.stream().filter(ManagerWorkLine::completed).count(),
                without.stream().filter(organization -> organization.managerId() != null).count()
        );
    }
}
