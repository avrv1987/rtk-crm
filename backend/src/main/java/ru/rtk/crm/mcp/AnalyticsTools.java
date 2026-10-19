package ru.rtk.crm.mcp;

import static ru.rtk.crm.mcp.McpTools.integerProperty;
import static ru.rtk.crm.mcp.McpTools.property;
import static ru.rtk.crm.mcp.McpTools.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.mcp.McpTools.Args;
import ru.rtk.crm.mcp.McpTools.Definition;
import ru.rtk.crm.mcp.McpTools.Page;
import ru.rtk.crm.mcp.McpTools.Result;
import ru.rtk.crm.report.LearningDynamics;
import ru.rtk.crm.report.LearningDynamicsService;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportFormat;
import ru.rtk.crm.report.ReportKind;
import ru.rtk.crm.report.ReportPreview;
import ru.rtk.crm.report.ReportRequest;
import ru.rtk.crm.report.ReportService;
import ru.rtk.crm.report.SigningPlan;
import ru.rtk.crm.report.SigningPlanService;
import ru.rtk.crm.work.LearningTrend;
import ru.rtk.crm.work.LearningTrendService;
import ru.rtk.crm.work.LmsSignalModels.LmsSignal;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;
import ru.rtk.crm.work.LmsSignalModels.LmsSignals;
import ru.rtk.crm.work.LmsSignalProperties;
import ru.rtk.crm.work.LmsSignalService;

// Инструменты обучения, востребованности, плана подписаний и паспортов показателей. Каждый вызывает тот же сервис, что
// экран или отчёт, и отдаёт его числа без пересчёта; неизвестное остаётся null («нет данных»), а не нулём.
// Регистрируются через LeaderTools, чтобы не менять общий список в McpTools.
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
class AnalyticsTools {
    static final String PASSPORTS_RESOURCE = "mcp/metrics.md";
    private static final Pattern CODE = Pattern.compile("[МM](\\d{2})");
    private static final Pattern FIELD = Pattern.compile("^- \\*\\*(.+?)\\.?\\*\\* (.*)$");
    private static final String PASSPORTS = " Паспорта показателей — docs/metrics.md (инструмент metric_passport): ";
    private static final Map<String, Object> LIMIT = integerProperty("Строк в списке, по умолчанию 25", 1, 200);

    private final LearningDynamicsService learningDynamicsService;
    private final LmsSignalService lmsSignalService;
    private final LearningTrendService learningTrendService;
    private final ReportService reportService;
    private final SigningPlanService signingPlanService;
    private final LmsSignalProperties lmsSignalProperties;
    private final McpProperties properties;
    private final String passports;

    AnalyticsTools(
            LearningDynamicsService learningDynamicsService,
            LmsSignalService lmsSignalService,
            LearningTrendService learningTrendService,
            ReportService reportService,
            SigningPlanService signingPlanService,
            LmsSignalProperties lmsSignalProperties,
            McpProperties properties
    ) {
        this.learningDynamicsService = learningDynamicsService;
        this.lmsSignalService = lmsSignalService;
        this.learningTrendService = learningTrendService;
        this.reportService = reportService;
        this.signingPlanService = signingPlanService;
        this.lmsSignalProperties = lmsSignalProperties;
        this.properties = properties;
        this.passports = readPassports();
    }

    List<Definition> definitions() {
        return List.of(
                new Definition("learning_overview", "Динамика обучения",
                        "Обучение по месяцам, вузам и ИТ-программам из отчёта «Динамика обучения»: обучающиеся, завершили, "
                                + "статус известен, доля завершивших среди известных с основанием, потоки без данных. "
                                + "Месяц без наблюдения — null («нет данных»), а не 0." + PASSPORTS + "М12, М13.",
                        schema(Map.of(
                                "from", property("string", "Начало периода, ГГГГ-ММ-ДД; по умолчанию 12 месяцев до to"),
                                "to", property("string", "Конец периода, ГГГГ-ММ-ДД, не позже сегодня; по умолчанию сегодня"),
                                "organizationIds", LeaderTools.list("Вузы", "uuid"),
                                "programIds", LeaderTools.list("ИТ-программы", "uuid"),
                                "limit", LIMIT)),
                        McpTools.KAM_AND_MANAGEMENT, this::learningOverview),
                new Definition("lms_signals", "Сигналы LMS",
                        "Действующие сигналы LMS, как в «Контроле» и ленте КАМ: причина, поток, данные и время наблюдения, "
                                + "признак устаревших данных. «Данных о завершении недостаточно» — отдельным списком."
                                + PASSPORTS + "М12, М13.",
                        schema(Map.of("limit", LIMIT)),
                        McpTools.KAM_AND_MANAGEMENT, this::lmsSignals),
                new Definition("learning_trend", "Тренд обучения",
                        "Изменение числа обучающихся за период по сопоставимым потокам — по командам и программам; потоки без "
                                + "базы сравнения — отдельно, в изменение не входят." + PASSPORTS + "«Тренд обучения».",
                        schema(Map.of("days", integerProperty("Длина периода, дней; по умолчанию как на экране", 7, 730))),
                        McpTools.LEADERS, this::learningTrend),
                new Definition("program_demand", "Востребованность программ",
                        "Отчёт «Востребованность программ»: заявки, оплаты, потоки и обучение отдельными колонками, как в "
                                + "отчёте; общего рейтинга нет. Заголовки колонок говорят источник и дату среза." + PASSPORTS
                                + "М12.",
                        schema(Map.of(
                                "from", property("string", "Начало периода, ГГГГ-ММ-ДД"),
                                "to", property("string", "Конец периода включительно, ГГГГ-ММ-ДД"),
                                "organizationIds", LeaderTools.list("Вузы", "uuid"),
                                "directionIds", LeaderTools.list("Направления", "uuid"),
                                "programIds", LeaderTools.list("Программы", "uuid"),
                                "managerIds", LeaderTools.list("КАМ вуза", "uuid"),
                                "organizationType", McpTools.enumProperty("Тип организации", "UNIVERSITY", "SCHOOL", "COLLEGE"),
                                "limit", LIMIT)),
                        McpTools.LEADERS, this::programDemand),
                new Definition("signing_plan", "План подписаний",
                        "План подписаний и продлений договоров за квартал или год, как в отчёте: итоги по командам и КАМ "
                                + "(подписание, продление, выполнено, просрочено, впереди) и строки плана.",
                        schema(Map.of(
                                "year", integerProperty("Год; по умолчанию текущий", 2000, 2100),
                                "quarter", integerProperty("Квартал 1–4; без года и квартала — текущий квартал", 1, 4),
                                "limit", LIMIT)),
                        McpTools.LEADERS, this::signingPlan),
                new Definition("metric_passport", "Паспорт показателя",
                        "Паспорт показателя из docs/metrics.md по коду М01–М14: вопрос, объект счёта, числитель и знаменатель, "
                                + "время, фильтры, поведение при неизвестном, как считается сейчас и расхождение.",
                        schema(Map.of("code", property("string", "Код показателя, например М03")), "code"),
                        McpTools.KAM_AND_MANAGEMENT, this::metricPassport)
        );
    }

    private Result learningOverview(CrmProfile profile, Args args) {
        int limit = limit(args);
        List<UUID> organizations = LeaderTools.ids(args, "organizationIds");
        List<UUID> programs = LeaderTools.ids(args, "programIds");
        LearningDynamics dynamics = learningDynamicsService.dynamics(profile, args.date("from", null), args.date("to", null), null,
                organizations, programs);
        List<Map<String, Object>> months = new ArrayList<>();
        for (int index = 0; index < dynamics.months().size(); index++) {
            LearningDynamics.Month month = dynamics.months().get(index);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("month", month.key());
            line.put("asOf", month.asOf());
            line.put("participants", dynamics.totalParticipants().get(index));
            line.put("completed", dynamics.totalCompleted().get(index));
            line.put("known", dynamics.totalKnown().get(index));
            line.put("runsWithoutData", dynamics.totalRunsWithoutData().get(index));
            months.add(line);
        }
        Page<LearningDynamics.Row> rows = Page.of(dynamics.rows(), (long) dynamics.rows().size(), limit);
        Map<String, Object> filters = new LinkedHashMap<>();
        putIfPresent(filters, "organizationIds", organizations);
        putIfPresent(filters, "programIds", programs);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("months", months);
        data.put("rows", rows);
        data.put("basis", basis(dynamics.generatedAt(), dynamics.from(), dynamics.to(), filters, dynamics.notes(), "М12", "М13"));
        return new Result(data, months.size() + rows.returned(), List.of(
                "participants — обучающиеся в потоках месяца (участия, не уникальные люди); known — статус завершения известен; "
                        + "доля завершивших — completionPercent строки с основанием completionBasis. null — нет данных, а не 0.",
                "Покрытие (М12) — known из participants; при малом покрытии вывод о доле не делается.",
                "Это данные Moodle, а не оценка КАМ или вуза."));
    }

    private Result lmsSignals(CrmProfile profile, Args args) {
        int limit = limit(args);
        LmsSignals signals = lmsSignalService.list(profile);
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> unverified = new ArrayList<>();
        for (LmsSignal signal : signals.items()) {
            (signal.type() == LmsSignalType.COMPLETION_UNVERIFIED ? unverified : facts).add(signal(signal));
        }
        Page<Map<String, Object>> page = Page.of(facts, (long) facts.size(), limit);
        Page<Map<String, Object>> unverifiedPage = Page.of(unverified, (long) unverified.size(), limit);
        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("signals", signals.items().size());
        freshness.put("stale", signals.items().stream().filter(LmsSignal::stale).count());
        freshness.put("freshWithinHours", lmsSignalProperties.staleAfter().toHours());
        Map<String, Object> basis = basis(signals.calculatedAt(), null, null, Map.of(), List.of(), "М12", "М13");
        basis.put("freshness", freshness);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("signals", page);
        data.put("completionUnverified", unverifiedPage);
        data.put("basis", basis);
        return new Result(data, page.returned() + unverifiedPage.returned(), List.of(
                "stale = true — данные потока старше freshWithinHours часов; такой сигнал нужно перепроверить в LMS.",
                "completionUnverified — данных о завершении недостаточно (покрытие ниже порога): вывод о доле завершивших не "
                        + "делается.",
                "Скрытые сотрудниками сигналы не входят, как на экране."));
    }

    private Result learningTrend(CrmProfile profile, Args args) {
        Integer days = args.text("days") == null ? null : args.integer("days", 7, 730, 0);
        LearningTrend trend = learningTrendService.trend(profile, days);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", trend.total());
        data.put("teams", trend.teams());
        data.put("programs", trend.programs());
        data.put("growing", trend.growing());
        data.put("falling", trend.falling());
        Map<String, Object> basis = basis(trend.calculatedAt(), trend.from(), trend.to(), Map.of(), List.of(), "Тренд обучения");
        basis.put("days", trend.days());
        basis.put("runsWithoutData", trend.runsWithoutData());
        data.put("basis", basis);
        return new Result(data, trend.teams().size() + trend.programs().size(), List.of(
                "start, end и change — по сопоставимым потокам, у которых есть значение на начало периода; endWithoutBase — "
                        + "обучающиеся на конец в потоках без базы сравнения, в change они не входят.",
                "Изменение числа обучающихся — не оценка команды."));
    }

    private Result programDemand(CrmProfile profile, Args args) {
        int limit = limit(args);
        String type = args.text("organizationType");
        OrganizationType organizationType;
        try {
            organizationType = type == null ? null : OrganizationType.valueOf(type);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("organizationType", "Тип организации — UNIVERSITY, SCHOOL или COLLEGE");
        }
        ReportFilters filters = new ReportFilters(LeaderTools.ids(args, "organizationIds"), LeaderTools.ids(args, "directionIds"), false,
                LeaderTools.ids(args, "programIds"), false, List.of(), false, LeaderTools.ids(args, "managerIds"), false, List.of(),
                organizationType);
        LocalDate from = args.date("from", null);
        LocalDate to = args.date("to", null);
        ReportPreview preview = reportService.preview(profile,
                new ReportRequest(ReportKind.DEMAND, from, to, null, filters, null, ReportFormat.XLSX, null, null), 0, limit);
        Map<String, Object> applied = new LinkedHashMap<>();
        putIfPresent(applied, "organizationIds", filters.organizationIds());
        putIfPresent(applied, "directionIds", filters.directionIds());
        putIfPresent(applied, "programIds", filters.programIds());
        putIfPresent(applied, "managerIds", filters.managerIds());
        putIfPresent(applied, "organizationType", organizationType);
        Page<Map<String, Object>> rows = Page.of(preview.items(), preview.total(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", preview.columns().stream().map(column -> Map.of("key", column.id().name(), "title", column.title())).toList());
        data.put("rows", rows);
        data.put("basis", basis(preview.generatedAt(), from, to, applied, preview.notes(), "М12"));
        return new Result(data, rows.returned(), List.of(
                "Колонки — как в отчёте и файле; ключ строки — key колонки. Пустое значение — нет данных источника, а не 0.",
                "Строки в порядке отчёта; общего рейтинга программ нет: заявки, оплаты и обучение — разные вопросы."));
    }

    private Result signingPlan(CrmProfile profile, Args args) {
        int limit = limit(args);
        Integer year = args.text("year") == null ? null : args.integer("year", 2000, 2100, 0);
        Integer quarter = args.text("quarter") == null ? null : args.integer("quarter", 1, 4, 0);
        SigningPlan plan = signingPlanService.plan(profile, year, quarter);
        Page<SigningPlan.Row> rows = Page.of(plan.rows(), (long) plan.rows().size(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("byTeam", plan.byTeam());
        data.put("byManager", plan.byManager());
        data.put("rows", rows);
        Map<String, Object> basis = basis(plan.generatedAt(), plan.from(), plan.to(), Map.of(), plan.notes());
        basis.put("year", plan.year());
        basis.put("quarter", plan.quarter());
        data.put("basis", basis);
        return new Result(data, rows.returned(), List.of(
                "source: PLAN — плановая дата из карточки договора, EXPIRY — окончание действующего договора; state: DONE — "
                        + "выполнено, OVERDUE — дата прошла без подписания, UPCOMING — впереди.",
                "Это план и факт договоров, а не оценка КАМ."));
    }

    private Result metricPassport(CrmProfile profile, Args args) {
        String requested = args.text("code");
        Matcher code = requested == null ? null : CODE.matcher(requested.strip().toUpperCase(java.util.Locale.ROOT));
        if (code == null || !code.matches()) {
            throw new InteractionValidationException("code", "Укажите код показателя М01–М14");
        }
        Map<String, Object> passport = passport(passports, "М" + code.group(1));
        if (passport == null) {
            throw new InteractionValidationException("code", "Такого показателя нет: доступны М01–М14");
        }
        return new Result(passport, 1, List.of(
                "Паспорт — целевое определение; «Как считается сейчас» и «Расхождение» говорят, чем текущий расчёт от него "
                        + "отличается. До утверждения владельцем показатели не используются для оценки сотрудников."));
    }

    // Раздел «## М03. Название» до следующего заголовка второго уровня; поля — строки «- **Поле.** текст».
    static Map<String, Object> passport(String document, String code) {
        String[] lines = document.replace("\r\n", "\n").split("\n");
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].startsWith("## " + code + ".")) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (int next = index + 1; next < lines.length && !lines[next].startsWith("## "); next++) {
                Matcher field = FIELD.matcher(lines[next]);
                if (field.matches()) {
                    fields.put(field.group(1), field.group(2));
                }
            }
            Map<String, Object> passport = new LinkedHashMap<>();
            passport.put("code", code);
            passport.put("title", lines[index].substring(("## " + code + ".").length()).strip());
            passport.put("fields", fields);
            passport.put("source", "docs/metrics.md");
            return passport;
        }
        return null;
    }

    private static String readPassports() {
        try (InputStream input = AnalyticsTools.class.getClassLoader().getResourceAsStream(PASSPORTS_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Нет ресурса " + PASSPORTS_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Map<String, Object> signal(LmsSignal signal) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("type", signal.type());
        fact.put("title", signal.title());
        fact.put("reason", signal.message());
        fact.put("interactionId", signal.interactionId());
        fact.put("interaction", signal.interactionTitle());
        fact.put("organization", signal.organizationName());
        fact.put("manager", signal.ownerManagerName());
        fact.put("course", signal.courseName());
        fact.put("group", signal.groupName());
        fact.put("runStartsOn", signal.runStartsOn());
        fact.put("runEndsOn", signal.runEndsOn());
        fact.put("participants", signal.participants());
        fact.put("completed", signal.completed());
        fact.put("observedAt", signal.observedAt());
        fact.put("stale", signal.stale());
        return fact;
    }

    private static Map<String, Object> basis(Object calculatedAt, LocalDate from, LocalDate to, Map<String, Object> filters,
                                             List<String> serviceNotes, String... metrics) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("from", from);
        basis.put("to", to);
        basis.put("filters", filters);
        basis.put("metrics", List.of(metrics));
        basis.put("serviceNotes", serviceNotes);
        basis.put("calculatedAt", calculatedAt);
        basis.put("passports", "docs/metrics.md");
        return basis;
    }

    private int limit(Args args) {
        return args.integer("limit", 1, 200, properties.listLimit());
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof List<?> list && list.isEmpty())) {
            target.put(key, value);
        }
    }
}
