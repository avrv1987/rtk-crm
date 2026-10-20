package ru.rtk.crm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.PlanKind;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.LearningDynamics;
import ru.rtk.crm.report.LearningDynamicsService;
import ru.rtk.crm.report.ReportColumn;
import ru.rtk.crm.report.ReportColumnView;
import ru.rtk.crm.report.ReportKind;
import ru.rtk.crm.report.ReportPreview;
import ru.rtk.crm.report.ReportService;
import ru.rtk.crm.report.SigningPlan;
import ru.rtk.crm.report.SigningPlanService;
import ru.rtk.crm.work.LearningTrend;
import ru.rtk.crm.work.LearningTrendService;
import ru.rtk.crm.work.LmsLearningTestBeans;
import ru.rtk.crm.work.LmsSignalModels.LmsSignal;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;
import ru.rtk.crm.work.LmsSignalModels.LmsSignals;
import ru.rtk.crm.work.LmsSignalService;
import ru.rtk.crm.work.WorkAccessDeniedException;

// Инструменты обучения, востребованности, плана подписаний и паспортов: тот же вызов сервиса экрана с теми же
// параметрами и те же числа в ответе, без пересчёта; неизвестное — null, а не 0.
class AnalyticsToolsTest {
    private static final CrmProfile KAM = new CrmProfile(UUID.randomUUID(), UserRole.USER, UUID.randomUUID(), 0);
    private static final CrmProfile LEADER = new CrmProfile(UUID.randomUUID(), UserRole.LEADER, UUID.randomUUID(), 0);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T10:00:00+03:00");

    private final LearningDynamicsService dynamics = mock(LearningDynamicsService.class);
    private final LmsSignalService signals = mock(LmsSignalService.class);
    private final LearningTrendService trend = mock(LearningTrendService.class);
    private final ReportService reports = mock(ReportService.class);
    private final SigningPlanService signingPlan = mock(SigningPlanService.class);
    private final AnalyticsTools tools = new AnalyticsTools(dynamics, signals, trend, reports, signingPlan,
            LmsLearningTestBeans.PROPERTIES,
            new McpProperties(true, "crm-mcp", List.of(), 60, 25, 10, 90, "crm-mcp.write", Duration.ofHours(24), 10, 50, java.util.Map.of()), new ru.rtk.crm.settings.TestSystemSettings());

    @Test
    void learningOverviewPassesFiltersAndKeepsUnknownMonthsAsNull() {
        UUID organization = UUID.randomUUID();
        LocalDate from = LocalDate.parse("2026-08-01");
        LocalDate to = LocalDate.parse("2026-09-30");
        LearningDynamics.Row row = new LearningDynamics.Row("2026-09", "сентябрь 2026", organization, "Вуз", UUID.randomUUID(),
                "Java", 1, 0, 100L, 16L, 20L, 80L, "16 из 20 с известным статусом; данные по 20 из 100");
        when(dynamics.dynamics(eq(KAM), eq(from), eq(to), isNull(), eq(List.of(organization)), eq(List.of())))
                .thenReturn(new LearningDynamics(NOW, from, to, "Europe/Moscow", LearningDynamics.SeriesBy.ORGANIZATION,
                        List.of("Данные Moodle на 30.09.2026"),
                        List.of(new LearningDynamics.Month("2026-08", "август 2026", LocalDate.parse("2026-08-31")),
                                new LearningDynamics.Month("2026-09", "сентябрь 2026", to)),
                        List.of(), nullable(null, 100L), nullable(null, 16L), nullable(null, 20L), List.of(2L, 0L), List.of(row)));

        Map<String, Object> data = call(KAM, "learning_overview", Map.of("from", "2026-08-01", "to", "2026-09-30",
                "organizationIds", List.of(organization.toString())));

        List<?> months = (List<?>) data.get("months");
        assertThat(map(months.get(0))).containsEntry("participants", null).containsEntry("known", null)
                .containsEntry("runsWithoutData", 2L);
        assertThat(map(months.get(1))).containsEntry("participants", 100L).containsEntry("completed", 16L)
                .containsEntry("known", 20L);
        McpTools.Page<?> rows = (McpTools.Page<?>) data.get("rows");
        assertThat(rows.items()).isEqualTo(List.of(row));
        assertThat(map(data.get("basis"))).containsEntry("from", from).containsEntry("to", to)
                .containsEntry("metrics", List.of("М12", "М13"))
                .containsEntry("serviceNotes", List.of("Данные Moodle на 30.09.2026"));
        assertThat(map(map(data.get("basis")).get("filters"))).containsEntry("organizationIds", List.of(organization));
    }

    @Test
    void lmsSignalsSeparateUnverifiedCompletionAndReportFreshness() {
        when(signals.list(KAM)).thenReturn(new LmsSignals(NOW, List.of(
                signal(LmsSignalType.LOW_ACTIVITY, false), signal(LmsSignalType.COMPLETION_UNVERIFIED, true),
                signal(LmsSignalType.NO_STUDENTS, true))));

        Map<String, Object> data = call(KAM, "lms_signals", Map.of());

        assertThat(((McpTools.Page<?>) data.get("signals")).total()).isEqualTo(2);
        assertThat(((McpTools.Page<?>) data.get("completionUnverified")).total()).isEqualTo(1);
        assertThat(map(map(data.get("basis")).get("freshness"))).containsEntry("signals", 3).containsEntry("stale", 2L)
                .containsEntry("freshWithinHours", LmsLearningTestBeans.PROPERTIES.staleAfter().toHours());
    }

    @Test
    void learningTrendKeepsRunsWithoutBaseApartAndRefusalComesFromTheService() {
        LearningTrend.Item total = new LearningTrend.Item(null, "Всего", 100, 120, 20, 3, 15, 0, 0, 0, 0);
        when(trend.trend(LEADER, 30)).thenReturn(new LearningTrend(NOW, LocalDate.parse("2026-09-19"), LocalDate.parse("2026-10-19"),
                30, total, List.of(), List.of(), List.of(), List.of(), 3));
        when(trend.trend(eq(KAM), any())).thenThrow(new WorkAccessDeniedException());

        Map<String, Object> data = call(LEADER, "learning_trend", Map.of("days", 30));

        assertThat(data.get("total")).isEqualTo(total);
        assertThat(map(data.get("basis"))).containsEntry("days", 30).containsEntry("runsWithoutData", 3);
        assertThatThrownBy(() -> call(KAM, "learning_trend", Map.of())).isInstanceOf(WorkAccessDeniedException.class);
    }

    @Test
    void programDemandIsTheDemandReportWithItsColumnsAndFilters() {
        UUID program = UUID.randomUUID();
        Map<String, Object> item = Map.of("PROGRAM", "Java", "APPLICATIONS", 12L);
        when(reports.preview(eq(LEADER), argThat(request -> request.kind() == ReportKind.DEMAND
                && request.from().equals(LocalDate.parse("2026-01-01")) && request.to().equals(LocalDate.parse("2026-09-30"))
                && request.filters().programIds().equals(List.of(program))
                && request.filters().organizationType() == OrganizationType.UNIVERSITY && request.columns() == null), eq(0), eq(25)))
                .thenReturn(new ReportPreview(ReportKind.DEMAND, NOW, List.of("Данные Moodle на 30.09.2026"),
                        List.of(new ReportColumnView(ReportColumn.APPLICATIONS, "Заявки (сайт, по дате подачи)", "нет данных")),
                        List.of(item), 0, 25, 40, List.of()));

        Map<String, Object> data = call(LEADER, "program_demand", Map.of("from", "2026-01-01", "to", "2026-09-30",
                "programIds", List.of(program.toString()), "organizationType", "UNIVERSITY"));

        assertThat(data.get("columns")).isEqualTo(List.of(Map.of("key", "APPLICATIONS", "title", "Заявки (сайт, по дате подачи)")));
        McpTools.Page<?> rows = (McpTools.Page<?>) data.get("rows");
        assertThat(rows.items()).isEqualTo(List.of(item));
        assertThat(rows.total()).isEqualTo(40);
        assertThat(rows.isComplete()).isFalse();
    }

    @Test
    void signingPlanReturnsTheReportTotalsAndRows() {
        SigningPlan.Totals team = new SigningPlan.Totals(UUID.randomUUID(), "Команда А", null, 2, 1, 1, 1, 1);
        SigningPlan.Row row = new SigningPlan.Row(UUID.randomUUID(), UUID.randomUUID(), "Вуз", null, "Команда А", null, "КАМ", "Д-1",
                AgreementStatus.ACTIVE, PlanKind.RENEWAL, LocalDate.parse("2026-11-01"), SigningPlan.Source.EXPIRY,
                LocalDate.parse("2026-11-01"), SigningPlan.State.UPCOMING);
        when(signingPlan.plan(LEADER, 2026, 4)).thenReturn(new SigningPlan(NOW, "Europe/Moscow", 2026, 4,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), List.of(), List.of(row), List.of(), List.of(team)));

        Map<String, Object> data = call(LEADER, "signing_plan", Map.of("year", 2026, "quarter", 4));

        assertThat(data.get("byTeam")).isEqualTo(List.of(team));
        assertThat(((McpTools.Page<?>) data.get("rows")).items()).isEqualTo(List.of(row));
        assertThat(map(data.get("basis"))).containsEntry("year", 2026).containsEntry("quarter", 4);
    }

    @Test
    void metricPassportComesFromTheDocumentAndUnknownCodeIsAValidationError() {
        Map<String, Object> passport = call(KAM, "metric_passport", Map.of("code", "m03"));
        assertThat(passport).containsEntry("code", "М03").containsEntry("title", "Просроченные следующие шаги");
        assertThat(map(passport.get("fields"))).containsKeys("Вопрос", "Неизвестное", "Как считается сейчас");

        for (String code : List.of("М99", "М98", "вчера")) {
            assertThatThrownBy(() -> call(KAM, "metric_passport", Map.of("code", code)))
                    .isInstanceOf(InteractionValidationException.class);
        }
    }

    // Паспорта в jar — копия docs/metrics.md (docs не входит в контекст сборки backend); расхождение ловит этот тест.
    @Test
    void bundledPassportsAreTheSameAsTheDocument() throws Exception {
        String bundled = Files.readString(Path.of("src/main/resources", AnalyticsTools.PASSPORTS_RESOURCE), StandardCharsets.UTF_8);
        // В публичном репозитории внутренних docs нет: сверка с документом только там, где он есть.
        Path documentPath = Path.of("..", "docs", "metrics.md");
        if (Files.exists(documentPath)) {
            String document = Files.readString(documentPath, StandardCharsets.UTF_8);
            assertThat(bundled.replace("\r\n", "\n")).isEqualTo(document.replace("\r\n", "\n"));
        }
        for (int number = 1; number <= 14; number++) {
            assertThat(AnalyticsTools.passport(bundled, "М%02d".formatted(number))).as("М%02d", number).isNotNull();
        }
    }

    private Map<String, Object> call(CrmProfile profile, String name, Map<String, Object> arguments) {
        McpTools.Definition definition = tools.definitions().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
        return map(definition.handler().apply(new McpCaller(profile, "test", Set.of(), "request"), new McpTools.Args(arguments)).data());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<Long> nullable(Long first, Long second) {
        List<Long> values = new ArrayList<>();
        values.add(first);
        values.add(second);
        return values;
    }

    private static LmsSignal signal(LmsSignalType type, boolean stale) {
        return new LmsSignal(type.name(), type, "Сигнал", "Причина", UUID.randomUUID(), "Работа", UUID.randomUUID(), "Вуз",
                UUID.randomUUID(), "КАМ", "Занятия", UUID.randomUUID(), "Курс", "Группа", LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-12-31"), 20, null, NOW, stale, null, null, false, false);
    }
}
