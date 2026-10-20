package ru.rtk.crm.mcp;

import static ru.rtk.crm.mcp.McpTools.enumProperty;
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
import java.util.stream.Stream;

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
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.mcp.McpTools.Args;
import ru.rtk.crm.mcp.McpTools.Definition;
import ru.rtk.crm.mcp.McpTools.Page;
import ru.rtk.crm.mcp.McpTools.Result;
import ru.rtk.crm.mcp.McpTools.UserText;
import ru.rtk.crm.report.ManagerAt;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.report.ManagerWorkOrganization;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.settings.SystemSetting;
import ru.rtk.crm.settings.SystemSettings;
import ru.rtk.crm.work.LmsSignalProperties;
import ru.rtk.crm.work.WorkAccessDeniedException;
import ru.rtk.crm.work.WorkControlModels.Control;
import ru.rtk.crm.work.WorkControlModels.ManagerBlock;
import ru.rtk.crm.work.WorkControlModels.TeamBlock;
import ru.rtk.crm.work.WorkControlModels.Totals;
import ru.rtk.crm.work.WorkControlModels.WorkItem;
import ru.rtk.crm.work.WorkControlService;
import ru.rtk.crm.work.WorkModels.TeamSummary;
import ru.rtk.crm.work.WorkModels.TeamsSummary;
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
    private static final String MODE_NOW = "NOW";
    private static final String MODE_PERIOD = "PERIOD";
    private static final String MANAGER_UNASSIGNED = "UNASSIGNED";
    private static final String METRICS_DOC = "metric_passport";
    private static final String PASSPORTS = " Паспорта показателей — инструмент " + METRICS_DOC + ": ";
    private static final String BASIS_NOTE = "basis — основание расчёта: режим, период, применённые отборы, правило КАМ строки, "
            + "область, считаются ли «Без работы», что берётся только на сейчас, покрытие и свежесть данных обучения в строках, "
            + "время расчёта. Те же параметры на экране «Контроль» и в отчёте «Работа менеджеров» дают те же числа.";

    private final WorkControlService workControlService;
    private final WorkService workService;
    private final AgreementRegistryService agreementRegistryService;
    private final WorkProperties workProperties;
    private final McpProperties properties;
    private final LmsSignalProperties lmsSignalProperties;
    private final AnalyticsTools analyticsTools;
    private final SystemSettings settings;

    LeaderTools(
            WorkControlService workControlService,
            WorkService workService,
            AgreementRegistryService agreementRegistryService,
            WorkProperties workProperties,
            McpProperties properties,
            LmsSignalProperties lmsSignalProperties,
            AnalyticsTools analyticsTools,
            SystemSettings settings
    ) {
        this.workControlService = workControlService;
        this.workService = workService;
        this.agreementRegistryService = agreementRegistryService;
        this.workProperties = workProperties;
        this.properties = properties;
        this.lmsSignalProperties = lmsSignalProperties;
        this.analyticsTools = analyticsTools;
        this.settings = settings;
    }

    // Инструменты обучения, востребованности и паспортов (AnalyticsTools) регистрируются отсюда, чтобы не менять общий
    // список в McpTools.
    List<Definition> definitions() {
        return Stream.concat(Stream.of(
                new Definition("team_control", "Сводка «Контроля»",
                        "Сводка «Контроля» по командам и менеджерам: вузы, взаимодействия, просрочки, без шага, «на этапе "
                                + "дольше», «Без работы», «Требует назначения». Режим, период и отборы — как на экране, итоги "
                                + "те же; basis — основание расчёта." + PASSPORTS + "М01–М06, М09.",
                        sliceSchema(Map.of("stuckDays", STUCK_DAYS)), McpTools.LEADERS, this::teamControl),
                new Definition("manager_breakdown", "Кто отстаёт и почему",
                        "По каждому менеджеру: причины отставания с фактами (просрочки, без шага, застряли, риски, договоры "
                                + "дольше нормы, сигналы LMS, вузы без работы). Режим, период и отборы — как в «Контроле». "
                                + "Без оценок сотрудников." + PASSPORTS + "М03, М04, М06, М09, М10, М12, М13.",
                        sliceSchema(Map.of(
                                "managerId", uuidProperty("Только этот менеджер в ответе"),
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
                                + "взаимодействия, подписанные и расторгнутые договоры. По умолчанию — текущая неделя; режим и "
                                + "отборы — как в «Контроле» и отчёте." + PASSPORTS + "М01, М07.",
                        sliceSchema(Map.of()), McpTools.LEADERS, this::periodSummary),
                new Definition("stuck_and_idle", "Застрявшие и без работы",
                        "Взаимодействия, которые на этапе дольше порога, и вузы с КАМ, но без идущей работы; число вузов, "
                                + "которым нужен КАМ. Режим, период и отборы — как в «Контроле»." + PASSPORTS + "М05, М06, М09.",
                        sliceSchema(Map.of(
                                "stuckDays", STUCK_DAYS,
                                "limit", integerProperty("Строк в каждом списке, по умолчанию 25", 1, 50))),
                        McpTools.LEADERS, this::stuckAndIdle)
        ), analyticsTools.definitions().stream()).toList();
    }

    private Result teamControl(CrmProfile profile, Args args) {
        int stuckDays = stuckDays(args);
        Slice slice = slice(profile, args, null, null);
        Control control = control(profile, slice);
        boolean idleCounted = control.idleOrganizations() != null;
        List<Map<String, Object>> teams = new ArrayList<>();
        int rows = 0;
        for (TeamBlock team : control.teams()) {
            List<Map<String, Object>> managers = new ArrayList<>();
            for (ManagerBlock manager : team.managers()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("managerId", manager.managerId());
                line.put("manager", managerName(manager));
                line.put("counts", counts(manager.totals(), manager.works(), stuckDays, idleCounted));
                managers.add(line);
            }
            rows += managers.size();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("teamId", team.teamId());
            block.put("team", team.teamName());
            block.put("counts", counts(team.totals(), team.managers().stream().flatMap(manager -> manager.works().stream()).toList(),
                    stuckDays, idleCounted));
            block.put("managers", managers);
            teams.add(block);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("byTeams", control.byTeams());
        data.put("stuckDays", stuckDays);
        data.put("total", counts(control.total(), allWorks(control), stuckDays, idleCounted));
        data.put("organizationsRequiringAssignment", unassignedOrganizations(profile, stuckDays, slice.teamId()));
        data.put("teams", teams);
        data.put("basis", basis(slice, control));
        return new Result(data, rows, List.of(
                "counts: organizations — вузы, works — взаимодействия строк, overdue — просрочен шаг, thisWeek — шаг на этой "
                        + "неделе, noNextStep — без шага или срока, stuck — на этапе дольше stuckDays (кроме этапа договора на "
                        + "согласовании), withIssues — с проблемами или рисками, idleOrganizations — «Без работы» (null — при "
                        + "периоде или отборах не считается, а не ноль).",
                "organizationsRequiringAssignment — вузы без действующего КАМ («Требует назначения») на момент запроса, без "
                        + "отборов экрана; их работы — в строке менеджера «" + UNASSIGNED + "».",
                BASIS_NOTE,
                FACTS_ONLY));
    }

    private Result managerBreakdown(CrmProfile profile, Args args) {
        int stuckDays = stuckDays(args);
        int examples = args.integer("examples", 1, 10, 5);
        UUID managerId = args.uuid("managerId");
        Slice slice = slice(profile, args, null, null);
        Control control = control(profile, slice);
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
                        control.idleOrganizations() == null ? null : (long) idle.size(), examples));
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
        data.put("basis", basis(slice, control));
        return new Result(data, rows, List.of(
                "Каждая причина — число случаев (total) и до " + examples + " примеров с фактами из карточек.",
                "agreementDelayed — договор на согласовании дольше порога (цвет YELLOW или RED).",
                "idleOrganizations с total = null — при периоде или отборах «Без работы» не считается, а не ноль.",
                BASIS_NOTE,
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
        Slice slice = slice(profile, args, today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
        LocalDate from = slice.from();
        LocalDate to = slice.to();
        Control control = control(profile, slice);
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
        data.put("basis", basis(slice, control));
        return new Result(data, managers.size(), List.of(
                "Период — по Москве, обе даты включительно.",
                "agreements — вся область роли за период, без отборов и выбора команды: реестр договоров их не принимает.",
                BASIS_NOTE,
                "activity: works — идущие взаимодействия и те, по которым в периоде были события; moved — с переходом этапа; "
                        + "transitions — число переходов; created — созданные; completed — завершённые в периоде.",
                "agreements: transitions — смены статуса договоров, signed — подписаны, terminated — расторгнуты, "
                        + "averageApprovalDays — средний срок от отправки до подписания, longReview — согласование дольше нормы.",
                "Число записей — это активность регистрации в CRM, а не оценка качества работы."));
    }

    private Result stuckAndIdle(CrmProfile profile, Args args) {
        int stuckDays = stuckDays(args);
        int limit = args.integer("limit", 1, 50, properties.listLimit());
        Slice slice = slice(profile, args, null, null);
        Control control = control(profile, slice);
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
                .toList(), control.idleOrganizations() == null ? null : (long) idle.size(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stuckDays", stuckDays);
        data.put("stuck", stuckPage);
        data.put("idleOrganizations", idlePage);
        data.put("organizationsRequiringAssignment", unassignedOrganizations(profile, stuckDays, slice.teamId()));
        data.put("basis", basis(slice, control));
        return new Result(data, stuckPage.returned() + idlePage.returned(), List.of(
                "stuck — взаимодействия строк на текущем этапе не меньше stuckDays дней; этап договора, пока договор на "
                        + "согласовании, сюда не входит: там действуют пороги согласования.",
                "idleOrganizations — вузы с КАМ, но без идущего взаимодействия («Без работы»); total = null — при периоде или "
                        + "отборах не считается.",
                BASIS_NOTE,
                FACTS_ONLY));
    }

    private Control control(CrmProfile profile, Slice slice) {
        Control control = workControlService.control(profile, slice.filters(), slice.from(), slice.to());
        UUID teamId = slice.teamId();
        if (teamId == null) {
            return control;
        }
        // Команду «Руководство» выбирает на экране в браузере (controlModel.teamControl): остаются её строки, вузы
        // «Без работы», договоры и итог команды. Здесь то же правило поверх того же ответа сервиса.
        List<TeamBlock> teams = control.teams().stream().filter(team -> teamId.equals(team.teamId())).toList();
        return new Control(control.calculatedAt(), control.from(), control.to(), control.byTeams(), teams,
                teams.isEmpty() ? new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) : teams.getFirst().totals(),
                control.idleOrganizations() == null ? null : control.idleOrganizations().stream()
                        .filter(organization -> teamId.equals(organization.teamId())).toList(),
                control.agreements().stream().filter(row -> teamId.equals(row.teamId())).toList(), control.routes());
    }

    // «Требует назначения» на экране берётся из показателей команды или сводки команд — так же и здесь.
    private long unassignedOrganizations(CrmProfile profile, int stuckDays, UUID teamId) {
        if (profile.role() != UserRole.MANAGEMENT) {
            return workService.teamIndicators(profile, stuckDays).unassignedOrganizations();
        }
        TeamsSummary summary = workService.teamsSummary(profile, stuckDays);
        return teamId == null ? summary.total().unassignedOrganizations() : summary.teams().stream()
                .filter(team -> teamId.equals(team.teamId()))
                .mapToLong(TeamSummary::unassignedOrganizations)
                .sum();
    }

    // Режим, период и отборы в тех же значениях, что принимает экран «Контроль» (WorkControlApiController), плюс тип
    // организации из отчёта «Работа менеджеров». Руководителю чужая команда — отказ, как чужой объект в REST.
    private static Slice slice(CrmProfile profile, Args args, LocalDate defaultFrom, LocalDate defaultTo) {
        String mode = Objects.requireNonNullElse(args.text("mode"), MODE_NOW);
        ManagerAt managerAt = switch (mode) {
            case MODE_NOW -> ManagerAt.CURRENT;
            case MODE_PERIOD -> ManagerAt.PERIOD_END;
            default -> throw new InteractionValidationException("mode", "Режим «Контроля» — NOW или PERIOD");
        };
        UUID teamId = args.uuid("teamId");
        if (teamId != null && profile.role() != UserRole.MANAGEMENT && !teamId.equals(profile.teamId())) {
            throw new WorkAccessDeniedException();
        }
        List<String> managers = texts(args, "managerIds");
        String type = args.text("organizationType");
        OrganizationType organizationType;
        try {
            organizationType = type == null ? null : OrganizationType.valueOf(type);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("organizationType", "Тип организации — UNIVERSITY, SCHOOL или COLLEGE");
        }
        ReportFilters filters = new ReportFilters(
                ids(args, "organizationIds"),
                ids(args, "directionIds"),
                false,
                ids(args, "programIds"),
                false,
                ids(args, "productIds"),
                false,
                ids(managers.stream().filter(value -> !MANAGER_UNASSIGNED.equals(value)).toList(), "managerIds"),
                managers.contains(MANAGER_UNASSIGNED),
                texts(args, "stages"),
                organizationType
        ).withManagerAt(managerAt).normalized();
        LocalDate from = args.date("from", defaultFrom);
        LocalDate to = args.date("to", defaultTo);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        return new Slice(from, to, teamId, filters);
    }

    private static List<String> texts(Args args, String name) {
        Object value = args.values().get(name);
        if (value == null) {
            return List.of();
        }
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        return values.stream().filter(Objects::nonNull).map(item -> item.toString().strip()).filter(item -> !item.isEmpty()).toList();
    }

    static List<UUID> ids(Args args, String name) {
        return ids(texts(args, name), name);
    }

    private static List<UUID> ids(List<String> values, String name) {
        try {
            return values.stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(name, "Некорректный идентификатор");
        }
    }

    // Основание расчёта: по нему человек повторит то же число на экране или в выгрузке и увидит, что в нём не учтено.
    private Map<String, Object> basis(Slice slice, Control control) {
        ReportFilters filters = slice.filters();
        Map<String, Object> applied = new LinkedHashMap<>();
        putIfPresent(applied, "teamId", slice.teamId());
        putIfPresent(applied, "organizationIds", filters.organizationIds());
        putIfPresent(applied, "directionIds", filters.directionIds());
        putIfPresent(applied, "programIds", filters.programIds());
        putIfPresent(applied, "productIds", filters.productIds());
        List<String> managers = new ArrayList<>(filters.managerIds().stream().map(UUID::toString).toList());
        if (filters.includeNoManager()) {
            managers.add(MANAGER_UNASSIGNED);
        }
        putIfPresent(applied, "managerIds", managers);
        putIfPresent(applied, "stages", filters.stages());
        putIfPresent(applied, "organizationType", filters.organizationType());
        boolean period = slice.period();
        List<WorkItem> works = allWorks(control);
        Map<String, Object> learning = new LinkedHashMap<>();
        learning.put("works", works.size());
        learning.put("withLmsStreams", works.stream().filter(item -> item.learning() != null && item.learning().runs() > 0).count());
        learning.put("withFreshActivity", works.stream().filter(item -> item.learning() != null && item.learning().active7d() != null)
                .count());
        learning.put("freshWithinHours", lmsSignalProperties.with(settings).staleAfter().toHours());
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("mode", period ? MODE_PERIOD : MODE_NOW);
        basis.put("from", slice.from());
        basis.put("to", slice.to());
        basis.put("filters", applied);
        basis.put("ownerPolicy", period
                ? "«За период»: менеджер строки — КАМ вуза на конец периода по истории назначений; если период не закончился — "
                        + "текущий КАМ."
                : "«Сейчас»: менеджер строки — текущий КАМ вуза; вуз без действующего КАМ своей команды — «"
                        + UNASSIGNED + "».");
        basis.put("scope", "Текущая область роли: руководитель — своя команда, «Руководство» — все команды; исторический КАМ "
                + "доступа не расширяет.");
        basis.put("rows", slice.from() == null && slice.to() == null ? "идущие взаимодействия"
                : "взаимодействия, созданные до конца периода, которые сейчас активны или имели события в периоде");
        basis.put("idleCounted", control.idleOrganizations() != null);
        basis.put("nowOnly", List.of("organizationsRequiringAssignment"));
        basis.put("learningCoverage", learning);
        basis.put("calculatedAt", control.calculatedAt());
        basis.put("passports", METRICS_DOC);
        return basis;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof List<?> list && list.isEmpty())) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> sliceSchema(Map<String, Object> own) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", enumProperty("Режим, как в «Контроле»: NOW — «Сейчас» (текущий КАМ), PERIOD — «За период» "
                + "(КАМ на конец периода; без to считается как NOW). По умолчанию NOW", MODE_NOW, MODE_PERIOD));
        properties.put("from", property("string", "Начало периода, ГГГГ-ММ-ДД"));
        properties.put("to", property("string", "Конец периода включительно, ГГГГ-ММ-ДД"));
        properties.put("teamId", uuidProperty("Только эта команда; руководителю доступна только своя"));
        properties.put("managerIds", list("Менеджеры: идентификаторы или " + MANAGER_UNASSIGNED + " — «" + UNASSIGNED + "»", null));
        properties.put("organizationIds", list("Вузы", "uuid"));
        properties.put("directionIds", list("Направления", "uuid"));
        properties.put("programIds", list("Программы", "uuid"));
        properties.put("productIds", list("Продукты", "uuid"));
        properties.put("stages", list("Текущие этапы по названию", null));
        properties.put("organizationType", enumProperty("Тип организации", "UNIVERSITY", "SCHOOL", "COLLEGE"));
        properties.putAll(own);
        return schema(properties);
    }

    static Map<String, Object> list(String description, String format) {
        Map<String, Object> items = format == null ? Map.of("type", "string") : Map.of("type", "string", "format", format);
        return Map.of("type", "array", "items", items, "maxItems", 500, "description", description);
    }

    private record Slice(LocalDate from, LocalDate to, UUID teamId, ReportFilters filters) {
        // Как в WorkControlService: без конца периода режим «За период» не действует.
        boolean period() {
            return filters.managerAt() == ManagerAt.PERIOD_END && to != null;
        }
    }

    private int stuckDays(Args args) {
        return args.integer("stuckDays", 1, 3650, settings.value(SystemSetting.STUCK_DAYS, workProperties.stuckDays()));
    }

    // То же правило, что плитка «На этапе дольше» (ManagerWorkReport.matches и controlModel.isStuck): идущая работа,
    // и этап договора, пока договор на согласовании, не застрявший — там свои пороги. Норматив этапа важнее порога.
    static boolean stuck(WorkItem item, int stuckDays) {
        return item.status() != InteractionWorkStatus.COMPLETED && item.status() != InteractionWorkStatus.PAUSED
                && !item.stageUnderApproval() && item.daysOnStage() != null
                && item.daysOnStage() >= (item.stageNormDays() == null ? stuckDays : item.stageNormDays());
    }

    private static boolean agreementDelayed(WorkItem item) {
        return item.agreementApproval() != null && item.agreementApproval().delayed();
    }

    private static Map<String, Object> counts(Totals totals, List<WorkItem> works, int stuckDays, boolean idleCounted) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("organizations", totals.organizations());
        counts.put("works", totals.works());
        counts.put("overdue", totals.overdue());
        counts.put("thisWeek", totals.thisWeek());
        counts.put("noNextStep", totals.noNextStep());
        counts.put("inOrder", totals.inOrder());
        counts.put("stuck", works.stream().filter(item -> stuck(item, stuckDays)).count());
        counts.put("withIssues", totals.withIssues());
        counts.put("idleOrganizations", idleCounted ? totals.idle() : null);
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
