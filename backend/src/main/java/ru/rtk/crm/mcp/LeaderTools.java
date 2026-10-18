package ru.rtk.crm.mcp;

import static ru.rtk.crm.mcp.McpTools.integerProperty;
import static ru.rtk.crm.mcp.McpTools.property;
import static ru.rtk.crm.mcp.McpTools.schema;
import static ru.rtk.crm.mcp.McpTools.uuidProperty;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.Approval;
import ru.rtk.crm.agreement.AgreementModels.ApprovalColor;
import ru.rtk.crm.agreement.AgreementModels.PeriodReport;
import ru.rtk.crm.agreement.AgreementModels.RegistryQuery;
import ru.rtk.crm.agreement.AgreementModels.RegistryRow;
import ru.rtk.crm.agreement.AgreementRegistryService;
import ru.rtk.crm.agreement.AgreementService;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.mcp.McpTools.Args;
import ru.rtk.crm.mcp.McpTools.Definition;
import ru.rtk.crm.mcp.McpTools.Page;
import ru.rtk.crm.mcp.McpTools.Result;
import ru.rtk.crm.mcp.McpTools.UserText;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.report.ManagerWorkOrganization;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.work.WorkControlModels.Control;
import ru.rtk.crm.work.WorkControlModels.ManagerBlock;
import ru.rtk.crm.work.WorkControlModels.TeamBlock;
import ru.rtk.crm.work.WorkControlModels.Totals;
import ru.rtk.crm.work.WorkControlModels.WorkItem;
import ru.rtk.crm.work.WorkControlService;
import ru.rtk.crm.work.WorkProperties;
import ru.rtk.crm.work.WorkService;

// Аналитический помощник руководителя и «Руководства». Числа — те же серверные итоги, что на экране «Контроль»;
// инструменты только раскладывают их по менеджерам и прикладывают факты, без оценок сотрудников.
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
class LeaderTools {
    static final String UNASSIGNED = "Требует назначения";
    private static final String FACTS_ONLY = "Это факты CRM, а не оценка сотрудника. Если причина задержки не записана, "
            + "её нужно уточнить у менеджера: отсутствие записи не значит отсутствие работы.";
    private static final Map<String, Object> STUCK_DAYS = integerProperty(
            "Порог «на этапе дольше», дней; по умолчанию как в «Контроле»", 1, 3650);

    private final WorkControlService workControlService;
    private final WorkService workService;
    private final AgreementRegistryService agreementRegistryService;
    private final WorkProperties workProperties;
    private final McpProperties properties;

    LeaderTools(
            WorkControlService workControlService,
            WorkService workService,
            AgreementRegistryService agreementRegistryService,
            WorkProperties workProperties,
            McpProperties properties
    ) {
        this.workControlService = workControlService;
        this.workService = workService;
        this.agreementRegistryService = agreementRegistryService;
        this.workProperties = workProperties;
        this.properties = properties;
    }

    List<Definition> definitions() {
        return List.of(
                new Definition("team_control", "Сводка «Контроля»",
                        "Сводка «Контроля» по командам и менеджерам: вузы, взаимодействия, просрочки, без шага, «на этапе "
                                + "дольше», «Без работы», «Требует назначения». Итоги те же, что на экране.",
                        schema(Map.of("stuckDays", STUCK_DAYS)), McpTools.LEADERS, this::teamControl),
                new Definition("manager_breakdown", "Кто отстаёт и почему",
                        "По каждому менеджеру: причины отставания с фактами (просрочки, без шага, застряли, риски, договоры "
                                + "дольше нормы, сигналы LMS, вузы без работы). Без оценок сотрудников.",
                        schema(Map.of(
                                "managerId", uuidProperty("Только этот менеджер"),
                                "stuckDays", STUCK_DAYS,
                                "examples", integerProperty("Сколько примеров на причину, по умолчанию 5", 1, 10))),
                        McpTools.LEADERS, this::managerBreakdown),
                new Definition("agreements_in_review", "Договоры на согласовании",
                        "Договоры с вузами на согласовании, корректировке и подписании: сторона, круг, дни, цвет по порогам; "
                                + "и действующие договоры, срок которых скоро истекает.",
                        schema(Map.of("limit", integerProperty("Строк в каждом списке, по умолчанию 25", 1, 50))),
                        McpTools.KAM_AND_MANAGEMENT, this::agreementsInReview),
                new Definition("period_summary", "Итоги за период",
                        "Итоги за период по отчёту «Работа менеджеров» и договорам: переходы этапов, новые и завершённые "
                                + "взаимодействия, подписанные и расторгнутые договоры. По умолчанию — текущая неделя.",
                        schema(Map.of(
                                "from", property("string", "Начало периода, ГГГГ-ММ-ДД"),
                                "to", property("string", "Конец периода включительно, ГГГГ-ММ-ДД"))),
                        McpTools.LEADERS, this::periodSummary),
                new Definition("stuck_and_idle", "Застрявшие и без работы",
                        "Взаимодействия, которые на этапе дольше порога, и вузы с КАМ, но без идущей работы; число вузов, "
                                + "которым нужен КАМ.",
                        schema(Map.of(
                                "stuckDays", STUCK_DAYS,
                                "limit", integerProperty("Строк в каждом списке, по умолчанию 25", 1, 50))),
                        McpTools.LEADERS, this::stuckAndIdle)
        );
    }

    private Result teamControl(CrmProfile profile, Args args) {
        int stuckDays = stuckDays(args);
        Control control = control(profile, null, null);
        List<Map<String, Object>> teams = new ArrayList<>();
        int rows = 0;
        for (TeamBlock team : control.teams()) {
            List<Map<String, Object>> managers = new ArrayList<>();
            for (ManagerBlock manager : team.managers()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("managerId", manager.managerId());
                line.put("manager", managerName(manager));
                line.put("counts", counts(manager.totals(), manager.works(), stuckDays));
                managers.add(line);
            }
            rows += managers.size();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("teamId", team.teamId());
            block.put("team", team.teamName());
            block.put("counts", counts(team.totals(), team.managers().stream().flatMap(manager -> manager.works().stream()).toList(),
                    stuckDays));
            block.put("managers", managers);
            teams.add(block);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("byTeams", control.byTeams());
        data.put("stuckDays", stuckDays);
        data.put("total", counts(control.total(), allWorks(control), stuckDays));
        data.put("organizationsRequiringAssignment", unassignedOrganizations(profile, stuckDays));
        data.put("teams", teams);
        return new Result(data, rows, List.of(
                "counts: organizations — вузы, works — идущие взаимодействия, overdue — просрочен шаг, thisWeek — шаг на этой "
                        + "неделе, noNextStep — без шага или срока, stuck — на этапе дольше stuckDays (кроме этапа договора на "
                        + "согласовании), withIssues — с проблемами или рисками, idleOrganizations — «Без работы».",
                "organizationsRequiringAssignment — вузы без действующего КАМ («Требует назначения»); их работы — в строке "
                        + "менеджера «" + UNASSIGNED + "».",
                FACTS_ONLY));
    }

    private Result managerBreakdown(CrmProfile profile, Args args) {
        int stuckDays = stuckDays(args);
        int examples = args.integer("examples", 1, 10, 5);
        UUID managerId = args.uuid("managerId");
        Control control = control(profile, null, null);
        OffsetDateTime now = OffsetDateTime.now();
        List<Map<String, Object>> managers = new ArrayList<>();
        int rows = 0;
        for (TeamBlock team : control.teams()) {
            for (ManagerBlock manager : team.managers()) {
                if (managerId != null && !managerId.equals(manager.managerId())) {
                    continue;
                }
                List<WorkItem> works = manager.works();
                List<ManagerWorkOrganization> idle = idleOf(control, manager.managerId());
                Map<String, Object> reasons = new LinkedHashMap<>();
                reasons.put("overdue", reason(works, item -> item.due() == ManagerWorkDue.OVERDUE, examples,
                        Comparator.comparing(WorkItem::nextActionAt, Comparator.nullsLast(Comparator.naturalOrder())), now));
                reasons.put("noNextStep", reason(works, item -> item.due() == ManagerWorkDue.NO_NEXT_STEP, examples, null, now));
                reasons.put("stuck", reason(works, item -> stuck(item, stuckDays), examples, byDaysOnStage(), now));
                reasons.put("withIssues", reason(works, item -> item.problems() + item.risks() > 0, examples, null, now));
                reasons.put("agreementDelayed", reason(works, LeaderTools::agreementDelayed, examples, null, now));
                reasons.put("lmsSignals", reason(works, item -> item.learning() != null
                        && (item.learning().signals() > 0 || item.learning().mismatch() != null), examples, null, now));
                reasons.put("idleOrganizations", Page.of(idle.stream().map(ManagerWorkOrganization::name).toList(),
                        (long) idle.size(), examples));
                rows += reasons.values().stream().mapToInt(page -> ((Page<?>) page).returned()).sum();
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("team", team.teamName());
                line.put("managerId", manager.managerId());
                line.put("manager", managerName(manager));
                line.put("works", works.size());
                line.put("reasons", reasons);
                managers.add(line);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stuckDays", stuckDays);
        data.put("managers", managers);
        return new Result(data, rows, List.of(
                "Каждая причина — число случаев (total) и до " + examples + " примеров с фактами из карточек.",
                "agreementDelayed — договор на согласовании дольше порога (цвет YELLOW или RED).",
                FACTS_ONLY));
    }

    private Result agreementsInReview(CrmProfile profile, Args args) {
        int limit = args.integer("limit", 1, 50, properties.listLimit());
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        List<RegistryRow> review = agreementRegistryService.registry(profile, new RegistryQuery(
                List.of(AgreementStatus.REVIEW, AgreementStatus.CORRECTION, AgreementStatus.SIGNING), null, null, null, null, false));
        List<RegistryRow> expiring = agreementRegistryService.registry(profile, new RegistryQuery(
                List.of(AgreementStatus.ACTIVE), null, null, today.plusDays(workProperties.agreementNoticeDays()), null, false));
        Map<String, Long> byColor = new LinkedHashMap<>();
        for (ApprovalColor color : ApprovalColor.values()) {
            byColor.put(color.name(), review.stream()
                    .filter(row -> row.approval() != null && row.approval().color() == color)
                    .count());
        }
        Page<AgreementRow> inReview = Page.of(review.stream().map(LeaderTools::agreement).toList(), (long) review.size(), limit);
        Page<AgreementRow> soon = Page.of(expiring.stream().map(LeaderTools::agreement).toList(), (long) expiring.size(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inReview", inReview);
        data.put("inReviewByColor", byColor);
        data.put("expiringWithinDays", workProperties.agreementNoticeDays());
        data.put("expiring", soon);
        return new Result(data, inReview.returned() + soon.returned(), List.of(
                "approval: side — у кого договор (UNIVERSITY — у вуза, SCHOOL — у школы, SIGNING — на подписании), round — "
                        + "круг согласования, sideDays и sideLimitDays — дни у стороны и её норма, totalDays — всего с отправки, "
                        + "color — цвет по порогам CRM.",
                "Порядок — как в реестре договоров: сначала дольше всего в текущем статусе."));
    }

    private Result periodSummary(CrmProfile profile, Args args) {
        LocalDate today = LocalDate.now(WorkProperties.ZONE);
        LocalDate from = args.date("from", today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        LocalDate to = args.date("to", today);
        Control control = control(profile, from, to);
        PeriodReport agreements = agreementRegistryService.period(profile, from, to, null);
        List<Map<String, Object>> managers = new ArrayList<>();
        for (TeamBlock team : control.teams()) {
            for (ManagerBlock manager : team.managers()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("team", team.teamName());
                line.put("managerId", manager.managerId());
                line.put("manager", managerName(manager));
                line.put("activity", activity(manager.totals(), manager.works()));
                managers.add(line);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from);
        data.put("to", to);
        data.put("total", activity(control.total(), allWorks(control)));
        data.put("managers", managers);
        data.put("agreements", agreements.totals());
        return new Result(data, managers.size(), List.of(
                "Период — по Москве, обе даты включительно.",
                "activity: works — идущие взаимодействия и те, по которым в периоде были события; moved — с переходом этапа; "
                        + "transitions — число переходов; created — созданные; completed — завершённые в периоде.",
                "agreements: transitions — смены статуса договоров, signed — подписаны, terminated — расторгнуты, "
                        + "averageApprovalDays — средний срок от отправки до подписания, longReview — согласование дольше нормы.",
                "Число записей — это активность регистрации в CRM, а не оценка качества работы."));
    }

    private Result stuckAndIdle(CrmProfile profile, Args args) {
        int stuckDays = stuckDays(args);
        int limit = args.integer("limit", 1, 50, properties.listLimit());
        Control control = control(profile, null, null);
        OffsetDateTime now = OffsetDateTime.now();
        List<Map<String, Object>> stuck = new ArrayList<>();
        for (TeamBlock team : control.teams()) {
            for (ManagerBlock manager : team.managers()) {
                manager.works().stream()
                        .filter(item -> stuck(item, stuckDays))
                        .forEach(item -> stuck.add(fact(item, managerName(manager), now)));
            }
        }
        stuck.sort(Comparator.comparing((Map<String, Object> item) -> (Long) item.get("daysOnStage"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<ManagerWorkOrganization> idle = control.idleOrganizations() == null ? List.of() : control.idleOrganizations();
        Page<Map<String, Object>> stuckPage = Page.of(stuck, (long) stuck.size(), limit);
        Page<IdleOrganization> idlePage = Page.of(idle.stream()
                .map(organization -> new IdleOrganization(organization.id(), organization.name(), organization.teamName(),
                        organization.managerName()))
                .toList(), (long) idle.size(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stuckDays", stuckDays);
        data.put("stuck", stuckPage);
        data.put("idleOrganizations", idlePage);
        data.put("organizationsRequiringAssignment", unassignedOrganizations(profile, stuckDays));
        return new Result(data, stuckPage.returned() + idlePage.returned(), List.of(
                "stuck — идущие взаимодействия на текущем этапе не меньше stuckDays дней; этап договора, пока договор на "
                        + "согласовании, сюда не входит: там действуют пороги согласования.",
                "idleOrganizations — вузы с КАМ, но без идущего взаимодействия («Без работы»).",
                FACTS_ONLY));
    }

    private Control control(CrmProfile profile, LocalDate from, LocalDate to) {
        return workControlService.control(profile, ReportFilters.none(), from, to);
    }

    // «Требует назначения» на экране берётся из показателей команды или сводки команд — так же и здесь.
    private long unassignedOrganizations(CrmProfile profile, int stuckDays) {
        return profile.role() == UserRole.MANAGEMENT
                ? workService.teamsSummary(profile, stuckDays).total().unassignedOrganizations()
                : workService.teamIndicators(profile, stuckDays).unassignedOrganizations();
    }

    private int stuckDays(Args args) {
        return args.integer("stuckDays", 1, 3650, workProperties.stuckDays());
    }

    // То же правило, что плитка «На этапе дольше» (ManagerWorkReport.matches и controlModel.isStuck): идущая работа,
    // и этап договора, пока договор на согласовании, не застрявший — там свои пороги.
    static boolean stuck(WorkItem item, int stuckDays) {
        return item.status() != InteractionWorkStatus.COMPLETED && item.status() != InteractionWorkStatus.PAUSED
                && !item.stageUnderApproval() && item.daysOnStage() != null && item.daysOnStage() >= stuckDays;
    }

    private static boolean agreementDelayed(WorkItem item) {
        return item.agreementApproval() != null && item.agreementApproval().delayed();
    }

    private static Map<String, Object> counts(Totals totals, List<WorkItem> works, int stuckDays) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("organizations", totals.organizations());
        counts.put("works", totals.works());
        counts.put("overdue", totals.overdue());
        counts.put("thisWeek", totals.thisWeek());
        counts.put("noNextStep", totals.noNextStep());
        counts.put("inOrder", totals.inOrder());
        counts.put("stuck", works.stream().filter(item -> stuck(item, stuckDays)).count());
        counts.put("withIssues", totals.withIssues());
        counts.put("idleOrganizations", totals.idle());
        return counts;
    }

    private static Map<String, Object> activity(Totals totals, List<WorkItem> works) {
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("works", totals.works());
        activity.put("moved", totals.moved());
        activity.put("transitions", works.stream().mapToLong(WorkItem::transitions).sum());
        activity.put("created", totals.created());
        activity.put("completed", totals.completed());
        return activity;
    }

    private static Page<Map<String, Object>> reason(List<WorkItem> works, Predicate<WorkItem> matches, int examples,
                                                   Comparator<WorkItem> order, OffsetDateTime now) {
        List<WorkItem> matched = new ArrayList<>(works.stream().filter(matches).toList());
        if (order != null) {
            matched.sort(order);
        }
        return Page.of(matched.stream().map(item -> fact(item, null, now)).toList(), (long) matched.size(), examples);
    }

    private static Comparator<WorkItem> byDaysOnStage() {
        return Comparator.comparing(WorkItem::daysOnStage, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static Map<String, Object> fact(WorkItem item, String manager, OffsetDateTime now) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("interactionId", item.interactionId());
        fact.put("organization", item.organizationName());
        fact.put("title", item.title());
        if (manager != null) {
            fact.put("manager", manager);
        }
        fact.put("stage", item.stage());
        fact.put("stagePosition", item.stagePosition());
        fact.put("stageTotal", item.stageTotal());
        fact.put("daysOnStage", item.daysOnStage());
        fact.put("status", item.status());
        fact.put("nextAction", UserText.of(item.nextAction()));
        fact.put("nextActionAt", item.nextActionAt());
        fact.put("due", item.due());
        fact.put("problems", item.problems());
        fact.put("risks", item.risks());
        fact.put("issues", UserText.of(item.issues()));
        fact.put("agreementStatus", item.agreementStatus());
        Approval approval = item.agreementApproval();
        fact.put("agreementApproval", approval);
        fact.put("lmsSignals", item.learning() == null ? null : item.learning().signalTitles());
        fact.put("observedAt", now);
        return fact;
    }

    private static List<ManagerWorkOrganization> idleOf(Control control, UUID managerId) {
        return control.idleOrganizations() == null || managerId == null ? List.of() : control.idleOrganizations().stream()
                .filter(organization -> managerId.equals(organization.managerId()))
                .toList();
    }

    private static List<WorkItem> allWorks(Control control) {
        return control.teams().stream()
                .flatMap(team -> team.managers().stream())
                .flatMap(manager -> manager.works().stream())
                .toList();
    }

    private static String managerName(ManagerBlock manager) {
        return manager.managerId() == null ? UNASSIGNED : Objects.requireNonNullElse(manager.managerName(), UNASSIGNED);
    }

    private static AgreementRow agreement(RegistryRow row) {
        return new AgreementRow(row.id(), row.organizationId(), row.organizationName(), row.number(), row.status().name(),
                row.status().title(), row.daysInStatus(), row.sentOn(), row.validUntil(), row.managerName(), row.approval());
    }

    record AgreementRow(UUID agreementId, UUID organizationId, String organization, String number, String status,
                        String statusTitle, long daysInStatus, LocalDate sentOn, LocalDate validUntil, String manager,
                        Approval approval) {
    }

    record IdleOrganization(UUID organizationId, String organization, String team, String manager) {
    }
}
