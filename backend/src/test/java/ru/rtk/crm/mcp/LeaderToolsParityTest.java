package ru.rtk.crm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementRegistryService;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.report.ManagerAt;
import ru.rtk.crm.report.ReportColumn;
import ru.rtk.crm.report.ReportFilters;
import ru.rtk.crm.report.ReportFormat;
import ru.rtk.crm.report.ReportKind;
import ru.rtk.crm.report.ReportPreview;
import ru.rtk.crm.report.ReportProperties;
import ru.rtk.crm.report.ReportRepository;
import ru.rtk.crm.report.ReportRequest;
import ru.rtk.crm.report.ReportScenario;
import ru.rtk.crm.report.ReportService;
import ru.rtk.crm.work.LmsLearningTestBeans;
import ru.rtk.crm.work.WorkAccessDeniedException;
import ru.rtk.crm.work.WorkControlModels.Control;
import ru.rtk.crm.work.WorkControlModels.ManagerBlock;
import ru.rtk.crm.work.WorkControlModels.TeamBlock;
import ru.rtk.crm.work.WorkControlModels.Totals;
import ru.rtk.crm.work.WorkControlService;
import ru.rtk.crm.work.WorkProperties;
import ru.rtk.crm.work.WorkService;

// Сверка «MCP = экран = файл»: инструменты руководителя на настоящем сервисе «Контроля» и отчёте «Работа менеджеров»
// с одними и теми же режимом, периодом и отборами дают одни и те же итоги.
@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:mcp_parity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        OrganizationAssignmentRepository.class,
        ReportRepository.class,
        ReportService.class,
        LmsLearningTestBeans.class,
        WorkControlService.class,
        ru.rtk.crm.agreement.AgreementRepository.class,
        ru.rtk.crm.agreement.AgreementApproval.class,
        ru.rtk.crm.agreement.AgreementStages.class,
        LeaderToolsParityTest.PropertiesConfiguration.class
})
class LeaderToolsParityTest {
    private static final LocalDate SEPTEMBER_FIRST = LocalDate.parse("2026-09-01");
    private static final LocalDate SEPTEMBER_LAST = LocalDate.parse("2026-09-30");
    private static final UUID IDLE_COLLEGE = UUID.fromString("00000000-0000-0000-0000-000000000199");
    private static final CrmProfile MANAGEMENT = new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0);

    @Autowired
    private WorkControlService workControlService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LeaderTools tools;

    @BeforeEach
    void setUp() {
        ReportScenario.prepare(jdbcTemplate);
        // Колледж с КАМ и без идущей работы: попадает в «Без работы» и проверяет отбор по типу организации.
        ReportScenario.organization(jdbcTemplate, IDLE_COLLEGE, "Колледж без работы", ReportScenario.TEAM_A, ReportScenario.MANAGER_A);
        jdbcTemplate.update("UPDATE organizations SET type = 'COLLEGE' WHERE id IN (?, ?)", IDLE_COLLEGE,
                ReportScenario.ORGANIZATION_A_UNASSIGNED);
        // Вуз передан другому КАМ после сентября: режимы «Сейчас» и «За период» дают разных менеджеров.
        ReportScenario.assignment(jdbcTemplate, ReportScenario.ORGANIZATION_A, ReportScenario.MANAGER_A, "Анна Кузнецова",
                ReportScenario.MANAGER_A2, "Борис Смирнов", java.time.OffsetDateTime.parse("2026-10-05T10:00:00+03:00"));
        tools = new LeaderTools(workControlService, mock(WorkService.class, RETURNS_DEEP_STUBS),
                mock(AgreementRegistryService.class, RETURNS_DEEP_STUBS),
                new WorkProperties(30, 14, 1, 3, 60, 25, 12, 90),
                new McpProperties(true, "crm-mcp", List.of(), 60, 25, 10, 90, "crm-mcp.write", Duration.ofHours(24), 10, 50, java.util.Map.of()),
                LmsLearningTestBeans.PROPERTIES, mock(AnalyticsTools.class), new ru.rtk.crm.settings.TestSystemSettings());
    }

    @Test
    void teamControlMatchesControlScreenAndManagerWorkReportForEveryModePeriodAndFilter() {
        List<Map<String, Object>> filterSets = List.of(
                Map.of(),
                Map.of("productIds", List.of(ReportScenario.PRODUCT_Y.toString())),
                Map.of("programIds", List.of(ReportScenario.PROGRAM.toString())),
                Map.of("directionIds", List.of(ReportScenario.DIRECTION.toString())),
                Map.of("managerIds", List.of(ReportScenario.MANAGER_A.toString())),
                Map.of("managerIds", List.of(ReportScenario.MANAGER_A2.toString(), "UNASSIGNED")),
                Map.of("stages", List.of("Встреча")),
                Map.of("organizationType", "UNIVERSITY"),
                Map.of("organizationType", "COLLEGE"),
                Map.of("organizationIds", List.of(ReportScenario.ORGANIZATION_A.toString())));
        int compared = 0;
        for (CrmProfile profile : List.of(ReportScenario.LEADER_A_PROFILE, MANAGEMENT)) {
            for (String mode : List.of("NOW", "PERIOD")) {
                for (LocalDate[] period : List.of(new LocalDate[]{null, null}, new LocalDate[]{SEPTEMBER_FIRST, SEPTEMBER_LAST})) {
                    for (Map<String, Object> filterSet : filterSets) {
                        Map<String, Object> arguments = new HashMap<>(filterSet);
                        arguments.put("mode", mode);
                        if (period[0] != null) {
                            arguments.put("from", period[0].toString());
                            arguments.put("to", period[1].toString());
                        }
                        String label = profile.role() + " " + arguments;
                        ReportFilters filters = filters(filterSet, mode);
                        Control control = workControlService.control(profile, filters, period[0], period[1]);
                        Map<String, Object> tool = call(profile, "team_control", arguments);
                        Map<String, Object> total = map(tool.get("total"));

                        Totals screen = control.total();
                        boolean idleCounted = control.idleOrganizations() != null;
                        assertThat(total).as(label).containsEntry("organizations", screen.organizations())
                                .containsEntry("works", screen.works())
                                .containsEntry("overdue", screen.overdue())
                                .containsEntry("thisWeek", screen.thisWeek())
                                .containsEntry("noNextStep", screen.noNextStep())
                                .containsEntry("inOrder", screen.inOrder())
                                .containsEntry("withIssues", screen.withIssues())
                                .containsEntry("idleOrganizations", idleCounted ? screen.idle() : null);
                        assertThat(managerWorks(tool)).as(label).isEqualTo(managerWorks(control));

                        ReportRequest request = new ReportRequest(ReportKind.MANAGER_WORK, period[0], period[1], null, filters,
                                List.of(ReportColumn.MANAGER, ReportColumn.INTERACTION), ReportFormat.XLSX, null, null);
                        ReportPreview preview = reportService.preview(profile, request, 0, 50);
                        assertThat(preview.total()).as(label).isEqualTo(screen.works());
                        if (screen.works() > 0) {
                            assertThat(preview.groupTotals().getLast().works()).as(label).isEqualTo(screen.works());
                            assertThat(preview.groupTotals().getLast().organizations()).as(label).isEqualTo(screen.organizations());
                        }

                        Map<String, Object> basis = map(tool.get("basis"));
                        assertThat(basis).as(label)
                                .containsEntry("mode", "PERIOD".equals(mode) && period[1] != null ? "PERIOD" : "NOW")
                                .containsEntry("from", period[0])
                                .containsEntry("to", period[1])
                                .containsEntry("idleCounted", idleCounted)
                                .containsKeys("ownerPolicy", "scope", "learningCoverage", "passports", "calculatedAt");
                        assertThat(map(basis.get("filters")).keySet()).as(label).containsExactlyInAnyOrderElementsOf(filterSet.keySet());
                        compared++;
                    }
                }
            }
        }
        assertThat(compared).isEqualTo(80);
    }

    @Test
    void modeChoosesTheManagerOfTheRowLikeTheScreen() {
        Map<String, Object> anna = Map.of("managerIds", List.of(ReportScenario.MANAGER_A.toString()),
                "from", SEPTEMBER_FIRST.toString(), "to", SEPTEMBER_LAST.toString());
        Map<String, Object> period = new HashMap<>(anna);
        period.put("mode", "PERIOD");

        assertThat(map(call(ReportScenario.LEADER_A_PROFILE, "team_control", period).get("total"))).containsEntry("works", 2L);
        assertThat(map(call(ReportScenario.LEADER_A_PROFILE, "team_control", anna).get("total")))
                .as("без режима — «Сейчас»: вуз уже у другого КАМ").containsEntry("works", 0L);
    }

    @Test
    void idleOrganizationsAreUnknownNotZeroWhenTheFilterCannotCountThem() {
        Map<String, Object> all = call(ReportScenario.LEADER_A_PROFILE, "team_control", Map.of());
        assertThat(map(all.get("total"))).containsEntry("idleOrganizations", 1L).containsEntry("organizations", 3L);

        Map<String, Object> universities = call(ReportScenario.LEADER_A_PROFILE, "team_control", Map.of("organizationType", "UNIVERSITY"));
        assertThat(map(universities.get("total"))).as("колледж без работы не входит в итог отбора университетов")
                .containsEntry("idleOrganizations", 0L).containsEntry("organizations", 1L);

        Map<String, Object> stuck = call(ReportScenario.LEADER_A_PROFILE, "stuck_and_idle", Map.of("productIds",
                List.of(ReportScenario.PRODUCT_Y.toString())));
        assertThat(((McpTools.Page<?>) stuck.get("idleOrganizations")).total()).isNull();
    }

    @Test
    void otherToolsUseTheSameRowsAsTheScreen() {
        Map<String, Object> arguments = Map.of("mode", "PERIOD", "from", SEPTEMBER_FIRST.toString(), "to", SEPTEMBER_LAST.toString(),
                "programIds", List.of(ReportScenario.PROGRAM.toString()));
        ReportFilters filters = filters(Map.of("programIds", List.of(ReportScenario.PROGRAM.toString())), "PERIOD");
        Control control = workControlService.control(ReportScenario.LEADER_A_PROFILE, filters, SEPTEMBER_FIRST, SEPTEMBER_LAST);

        Map<String, Object> period = call(ReportScenario.LEADER_A_PROFILE, "period_summary", arguments);
        assertThat(map(period.get("total"))).containsEntry("works", control.total().works())
                .containsEntry("moved", control.total().moved())
                .containsEntry("created", control.total().created())
                .containsEntry("completed", control.total().completed());

        Map<String, Object> breakdown = call(ReportScenario.LEADER_A_PROFILE, "manager_breakdown", arguments);
        assertThat(((List<?>) breakdown.get("managers")).stream().mapToInt(line -> ((Number) map(line).get("works")).intValue()).sum())
                .isEqualTo((int) control.total().works());

        Map<String, Object> stuck = call(ReportScenario.LEADER_A_PROFILE, "stuck_and_idle", Map.of("stuckDays", 1));
        long screenStuck = workControlService.control(ReportScenario.LEADER_A_PROFILE, filters(Map.of(), "NOW"), null, null).teams().stream()
                .flatMap(team -> team.managers().stream()).flatMap(manager -> manager.works().stream())
                .filter(item -> LeaderTools.stuck(item, 1)).count();
        assertThat(((McpTools.Page<?>) stuck.get("stuck")).total()).isEqualTo(screenStuck);
    }

    @Test
    void managementPicksATeamLikeTheScreenAndLeaderOnlyHisOwn() {
        Control control = workControlService.control(MANAGEMENT, filters(Map.of(), "NOW"), null, null);
        TeamBlock teamB = control.teams().stream().filter(team -> ReportScenario.TEAM_B.equals(team.teamId())).findFirst().orElseThrow();

        Map<String, Object> tool = call(MANAGEMENT, "team_control", Map.of("teamId", ReportScenario.TEAM_B.toString()));
        assertThat(map(tool.get("total"))).containsEntry("works", teamB.totals().works())
                .containsEntry("organizations", teamB.totals().organizations());
        assertThat((List<?>) tool.get("teams")).hasSize(1);

        assertThat(map(call(ReportScenario.LEADER_A_PROFILE, "team_control", Map.of("teamId", ReportScenario.TEAM_A.toString()))
                .get("total"))).containsEntry("works", 3L);
        assertThatThrownBy(() -> call(ReportScenario.LEADER_A_PROFILE, "team_control", Map.of("teamId", ReportScenario.TEAM_B.toString())))
                .isInstanceOf(WorkAccessDeniedException.class);
        for (String name : List.of("team_control", "manager_breakdown", "period_summary", "stuck_and_idle")) {
            assertThatThrownBy(() -> call(ReportScenario.MANAGER_A_PROFILE, name, Map.of())).as("КАМ без командной аналитики")
                    .isInstanceOf(WorkAccessDeniedException.class);
        }
    }

    // Востребованность программ: инструмент и отчёт на настоящем ReportService дают те же строки и итог.
    @Test
    void programDemandMatchesTheDemandReport() {
        ReportScenario.insertDemand(jdbcTemplate);
        AnalyticsTools analytics = new AnalyticsTools(mock(ru.rtk.crm.report.LearningDynamicsService.class),
                mock(ru.rtk.crm.work.LmsSignalService.class), mock(ru.rtk.crm.work.LearningTrendService.class), reportService,
                mock(ru.rtk.crm.report.SigningPlanService.class), LmsLearningTestBeans.PROPERTIES,
                new McpProperties(true, "crm-mcp", List.of(), 60, 25, 10, 90, "crm-mcp.write", Duration.ofHours(24), 10, 50, java.util.Map.of()), new ru.rtk.crm.settings.TestSystemSettings());
        McpTools.Definition definition = analytics.definitions().stream()
                .filter(item -> item.name().equals("program_demand")).findFirst().orElseThrow();
        for (CrmProfile profile : List.of(ReportScenario.LEADER_A_PROFILE, MANAGEMENT)) {
            for (Map<String, Object> filterSet : List.<Map<String, Object>>of(Map.of(),
                    Map.of("programIds", List.of(ReportScenario.PROGRAM.toString())), Map.of("organizationType", "COLLEGE"))) {
                Map<String, Object> tool = map(definition.handler()
                        .apply(new McpCaller(profile, "test", Set.of(), "request"), new McpTools.Args(filterSet)).data());
                ReportPreview preview = reportService.preview(profile, new ReportRequest(ReportKind.DEMAND, null, null,
                        null, filters(filterSet, "NOW").withManagerAt(null), null, ReportFormat.XLSX, null, null), 0, 25);
                McpTools.Page<?> rows = (McpTools.Page<?>) tool.get("rows");
                String label = profile.role() + " " + filterSet;
                assertThat(rows.total()).as(label).isEqualTo(preview.total());
                assertThat(rows.items()).as(label).isEqualTo(preview.items());
                if (filterSet.isEmpty()) {
                    assertThat(preview.total()).as("сверка не на пустом отчёте: " + label).isPositive();
                }
            }
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

    @SuppressWarnings("unchecked")
    private static ReportFilters filters(Map<String, Object> arguments, String mode) {
        List<String> managers = (List<String>) arguments.getOrDefault("managerIds", List.of());
        return new ReportFilters(
                ids(arguments, "organizationIds"),
                ids(arguments, "directionIds"),
                false,
                ids(arguments, "programIds"),
                false,
                ids(arguments, "productIds"),
                false,
                managers.stream().filter(value -> !"UNASSIGNED".equals(value)).map(UUID::fromString).toList(),
                managers.contains("UNASSIGNED"),
                (List<String>) arguments.getOrDefault("stages", List.of()),
                arguments.containsKey("organizationType") ? OrganizationType.valueOf((String) arguments.get("organizationType")) : null
        ).withManagerAt("PERIOD".equals(mode) ? ManagerAt.PERIOD_END : ManagerAt.CURRENT);
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> ids(Map<String, Object> arguments, String name) {
        return ((List<String>) arguments.getOrDefault(name, List.of())).stream().map(UUID::fromString).toList();
    }

    private static Map<String, Long> managerWorks(Control control) {
        Map<String, Long> works = new LinkedHashMap<>();
        for (TeamBlock team : control.teams()) {
            for (ManagerBlock manager : team.managers()) {
                works.put(team.teamId() + ":" + manager.managerId(), manager.totals().works());
            }
        }
        return works;
    }

    private static Map<String, Long> managerWorks(Map<String, Object> tool) {
        Map<String, Long> works = new LinkedHashMap<>();
        for (Object team : (List<?>) tool.get("teams")) {
            for (Object manager : (List<?>) map(team).get("managers")) {
                works.put(map(team).get("teamId") + ":" + map(manager).get("managerId"), (Long) map(map(manager).get("counts")).get("works"));
            }
        }
        return works;
    }

    @TestConfiguration
    static class PropertiesConfiguration {
        @Bean
        ModuleProperties moduleProperties() {
            return new ModuleProperties(new ModuleProperties.Toggle(true), null);
        }

        @Bean
        ReportProperties reportProperties() {
            return new ReportProperties(Path.of("target", "test-reports"), 10, 5, 1_000, 1_000);
        }
    }
}
