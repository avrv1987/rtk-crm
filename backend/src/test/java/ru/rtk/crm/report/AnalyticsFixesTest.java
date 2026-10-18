package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_TWO_PRODUCTS;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_UNASSIGNED;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_WITHOUT_LINKS;
import static ru.rtk.crm.report.ReportTestData.LEADER_A;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A2;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A_UNASSIGNED;
import static ru.rtk.crm.report.ReportTestData.PROGRAM;
import static ru.rtk.crm.report.ReportTestData.PROGRAM_DATA;
import static ru.rtk.crm.report.ReportTestData.RUN_ENDS;
import static ru.rtk.crm.report.ReportTestData.TEAM_A;
import static ru.rtk.crm.report.ReportTestData.TODAY;
import static ru.rtk.crm.report.ReportTestData.at;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.work.LearningTrendService;
import ru.rtk.crm.work.WorkControlModels.Control;
import ru.rtk.crm.work.WorkControlService;
import ru.rtk.crm.work.WorkRepository;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:analytics_fixes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        OrganizationAssignmentRepository.class,
        ReportRepository.class,
        ReportService.class,
        WorkControlService.class,
        WorkRepository.class,
        LearningHistoryRepository.class,
        LearningTrendService.class,
        ru.rtk.crm.agreement.AgreementRepository.class,
        ru.rtk.crm.agreement.AgreementApproval.class,
        ru.rtk.crm.agreement.AgreementStages.class,
        ru.rtk.crm.work.LmsLearningTestBeans.class,
        AnalyticsFixesTest.PropertiesConfiguration.class
})
class AnalyticsFixesTest {
    private static final LocalDate SEPTEMBER_FIRST = LocalDate.parse("2026-09-01");
    private static final LocalDate SEPTEMBER_LAST = LocalDate.parse("2026-09-30");

    @Autowired
    private ReportService reportService;

    @Autowired
    private WorkControlService workControlService;

    @Autowired
    private WorkRepository workRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private LearningTrendService trendService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbcTemplate);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, status VARCHAR(16) NOT NULL,
                    status_changed_at TIMESTAMP WITH TIME ZONE NOT NULL, valid_until DATE, sent_on DATE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreement_status_events (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreement_interactions (
                    agreement_id UUID NOT NULL, interaction_id UUID NOT NULL, PRIMARY KEY (agreement_id, interaction_id)
                )
                """);
        ReportTestData.insertScenario(jdbcTemplate);
    }

    @Test
    void archivedUniversitiesLeaveEveryReportAndPortfolioMatchesControl() {
        ReportTestData.insertDemand(jdbcTemplate);
        ReportTestData.assignment(jdbcTemplate, ORGANIZATION_A_UNASSIGNED, null, null, MANAGER_A2, "Борис Смирнов",
                at("2026-09-20T10:00:00+03:00"));
        assertThat(portfolioTotal()).isEqualTo(3);
        assertThat(controlWorks()).isEqualTo(3);

        jdbcTemplate.update("UPDATE organizations SET status = 'ARCHIVED' WHERE id = ?", ORGANIZATION_A_UNASSIGNED);

        assertThat(portfolioTotal()).isEqualTo(2).isEqualTo(controlWorks());
        assertThat(reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.PORTFOLIO,
                StatisticsGroupBy.ORGANIZATION, null, null, null, ReportFilters.none(), null, null)).total()).isEqualTo(2);
        assertThat(reportService.preview(LEADER_A_PROFILE, request(ReportKind.EVENTS), 0, 100).items())
                .noneMatch(item -> "Институт без КАМ".equals(item.get("ORGANIZATION")));
        assertThat(reportService.preview(LEADER_A_PROFILE, request(ReportKind.SNAPSHOT), 0, 100).total()).isEqualTo(2);
        assertThat(reportService.document(LEADER_A_PROFILE, request(ReportKind.DEMAND)).rows())
                .extracting(ReportRow::programName, ReportRow::applications, ReportRow::participants)
                .containsExactlyInAnyOrder(tuple("Java-разработчик", 3L, 8L), tuple(null, 4L, null));
    }

    @Test
    void learnerNumbersAreOneDefinitionCountedOverDifferentStreamSets() {
        ReportTestData.learning(jdbcTemplate, 11, null, ORGANIZATION_A, PROGRAM, 6, TODAY.minusDays(30), RUN_ENDS, "STUDENTS");
        ReportTestData.learning(jdbcTemplate, 12, null, ORGANIZATION_A, PROGRAM, 2, TODAY.minusDays(30), RUN_ENDS, "STUDENTS");
        ReportTestData.learning(jdbcTemplate, 13, null, ORGANIZATION_A, PROGRAM, 5, TODAY.minusDays(60), TODAY.minusDays(10), "STUDENTS");
        ReportTestData.learning(jdbcTemplate, 14, null, ORGANIZATION_A, PROGRAM_DATA, 3, TODAY.plusDays(10), TODAY.plusDays(100), "STUDENTS");
        ReportTestData.learning(jdbcTemplate, 15, null, ORGANIZATION_A, PROGRAM, 4, TODAY.minusDays(30), RUN_ENDS, "TEACHERS");
        jdbcTemplate.update("INSERT INTO programs (id, direction_id, name) VALUES (?, ?, ?)", PROGRAM_DATA,
                ReportTestData.DIRECTION, "Анализ данных");

        long allStreams = participants(request(ReportKind.DEMAND, null, null));
        long onToday = participants(request(ReportKind.DEMAND, TODAY, TODAY));

        assertThat(allStreams).isEqualTo(16);
        assertThat(onToday).isEqualTo(8);
        var scope = organizationRepository.visibilityScope(LEADER_A_PROFILE).orElseThrow();
        assertThat(workRepository.countOrganizationsByTeam(scope, TODAY).stream()
                .filter(team -> team.teamId().equals(TEAM_A)).findFirst().orElseThrow().participants()).isEqualTo(allStreams);
        assertThat(trendService.trend(LEADER_A_PROFILE, 30).total().end()).isEqualTo(onToday);

        jdbcTemplate.update("UPDATE organizations SET status = 'ARCHIVED' WHERE id = ?", ORGANIZATION_A);
        assertThat(participants(request(ReportKind.DEMAND, null, null))).isZero();
        assertThat(trendService.trend(LEADER_A_PROFILE, 30).total().end()).isZero();
        assertThat(workRepository.countOrganizationsByTeam(scope, TODAY).stream()
                .filter(team -> team.teamId().equals(TEAM_A)).findFirst().orElseThrow().participants()).isZero();
    }

    @Test
    void demandSliceDateIsNeverInTheFutureAndPlannedStreamsAreNotCountedAsParallel() {
        ReportTestData.insertDemand(jdbcTemplate);
        LocalDate future = TODAY.plusDays(60);

        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, request(ReportKind.DEMAND, SEPTEMBER_FIRST, future), 0, 50);

        String today = TODAY.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        assertThat(preview.columns()).extracting(ReportColumnView::title)
                .contains("Обучающиеся в потоках периода (Moodle, на " + today + ")", "Параллельные потоки (Moodle, на " + today + ")");
        assertThat(preview.notes()).anyMatch(note -> note.startsWith("Данные Moodle на " + today)
                && note.contains("период ещё не закончился"));
        assertThat(reportService.document(LEADER_A_PROFILE, request(ReportKind.DEMAND, SEPTEMBER_FIRST, future)).rows())
                .extracting(ReportRow::programName, ReportRow::parallelRuns)
                .containsExactlyInAnyOrder(tuple("Java-разработчик", 2L), tuple(null, null), tuple("Анализ данных", 0L));
        assertThat(reportService.preview(LEADER_A_PROFILE, request(ReportKind.DEMAND, TODAY.minusDays(40), TODAY.minusDays(30)), 0, 50)
                .notes()).noneMatch(note -> note.contains("период ещё не закончился"));
    }

    @Test
    void stagesFollowTheRouteWithEmptyStagesAsZeroAndForeignStagesAtTheEnd() {
        UUID template = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workflow_templates (id, name, default_template) VALUES (?, 'Основной', TRUE)", template);
        String[] route = {"Поиск контакта", "Уточнение актуальности", "Встреча", "Подписание"};
        for (int order = 0; order < route.length; order++) {
            jdbcTemplate.update("INSERT INTO workflow_template_stages (id, template_id, stage_order, name) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), template, order, route[order]);
        }
        jdbcTemplate.update("UPDATE interaction_stages SET name = 'Особый этап' WHERE interaction_id = ? AND stage_order = 0",
                INTERACTION_UNASSIGNED);

        StatisticsResult byStage = reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.PORTFOLIO,
                StatisticsGroupBy.STAGE, null, null, null, ReportFilters.none(), null, null));

        assertThat(byStage.items()).extracting(StatisticsResult.Item::label, StatisticsResult.Item::count).containsExactly(
                tuple("Поиск контакта", 1L), tuple("Уточнение актуальности", 0L), tuple("Встреча", 1L),
                tuple("Подписание", 0L), tuple("Особый этап", 1L));
        ReportFilters meeting = new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false,
                List.of("Встреча"), null);
        assertThat(reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.PORTFOLIO, StatisticsGroupBy.STAGE,
                null, null, null, meeting, null, null)).items())
                .extracting(StatisticsResult.Item::label, StatisticsResult.Item::count).containsExactly(tuple("Встреча", 1L));
    }

    @Test
    void monthlyLinesKeepFiveLargestAndMergeTheRestIntoOthers() throws Exception {
        List<StatisticsResult.Series> series = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            series.add(new StatisticsResult.Series("s" + index, "Вуз " + index, false, List.of((long) (10 - index), 1L)));
        }
        series.add(new StatisticsResult.Series("unspecified", "Не указано", true, List.of(2L, 0L)));

        List<StatisticsResult.Series> merged = ReportService.withOthers(series, 2);

        assertThat(merged).extracting(StatisticsResult.Series::label).containsExactly(
                "Вуз 1", "Вуз 2", "Вуз 3", "Вуз 4", "Вуз 5", "Остальные (3)", "Не указано");
        assertThat(merged.get(5).counts()).containsExactly(4L + 3L + 2L, 3L);
        assertThat(merged.get(5).others()).isTrue();
        assertThat(merged).hasSizeLessThanOrEqualTo(StatisticsResult.MAX_CHART_SERIES);
        assertThat(ReportService.withOthers(series.subList(0, 5), 2)).isEqualTo(series.subList(0, 5));

        for (int index = 0; index < 7; index++) {
            addMonthlyWork(index);
        }
        StatisticsResult result = reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.EVENTS,
                StatisticsGroupBy.MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST, null, ReportFilters.none(), null,
                StatisticsGroupBy.ORGANIZATION));
        assertThat(result.series()).extracting(StatisticsResult.Series::others).containsOnlyOnce(true);
        assertThat(result.series().stream().filter(item -> !item.others() && !item.unspecified())).hasSize(5);
        assertThat(result.series().stream().filter(StatisticsResult.Series::others).findFirst().orElseThrow().label())
                .startsWith("Остальные (");
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        new StatisticsChartWriter().writePng(result, true, png);
        assertThat(png.size()).isPositive();
    }

    @Test
    void managerWorkBelongsToTheManagerWhoLedTheUniversityAtPeriodEnd() {
        ReportTestData.assignment(jdbcTemplate, ORGANIZATION_A, MANAGER_A, "Анна Кузнецова", MANAGER_A2, "Борис Смирнов",
                at("2026-10-05T10:00:00+03:00"));

        ReportPreview september = reportService.preview(LEADER_A_PROFILE, managerWork(SEPTEMBER_FIRST, SEPTEMBER_LAST), 0, 50);
        ReportPreview untilThird = reportService.preview(LEADER_A_PROFILE,
                managerWork(SEPTEMBER_FIRST, LocalDate.parse("2026-10-10")), 0, 50);
        ReportPreview open = reportService.preview(LEADER_A_PROFILE, managerWork(SEPTEMBER_FIRST, null), 0, 50);

        assertThat(september.columns().getFirst().title()).isEqualTo("Менеджер на конец периода");
        assertThat(september.items()).filteredOn(item -> "Университет «Альфа»".equals(item.get("ORGANIZATION")))
                .extracting(item -> item.get("MANAGER")).containsOnly("Анна Кузнецова");
        assertThat(untilThird.items()).filteredOn(item -> "Университет «Альфа»".equals(item.get("ORGANIZATION")))
                .extracting(item -> item.get("MANAGER")).containsOnly("Борис Смирнов");
        assertThat(open.columns().getFirst().title()).isEqualTo("Текущий менеджер");
        assertThat(open.items()).filteredOn(item -> "Университет «Альфа»".equals(item.get("ORGANIZATION")))
                .extracting(item -> item.get("MANAGER")).containsOnly("Борис Смирнов");
        assertThat(september.notes()).anyMatch(note -> note.contains("ведущий вуз на конец периода"));

        ReportRequest byFormerManager = new ReportRequest(ReportKind.MANAGER_WORK, SEPTEMBER_FIRST, SEPTEMBER_LAST, null,
                new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(MANAGER_A), false,
                        List.of(), null), null, ReportFormat.XLSX, null, null);
        assertThat(reportService.preview(LEADER_A_PROFILE, byFormerManager, 0, 50).total()).isEqualTo(2);

        Control control = workControlService.control(LEADER_A_PROFILE, ReportFilters.none(), SEPTEMBER_FIRST, SEPTEMBER_LAST);
        assertThat(control.teams().getFirst().managers())
                .filteredOn(block -> "Борис Смирнов".equals(block.managerName()))
                .singleElement().satisfies(block -> assertThat(block.works()).hasSize(2));
    }

    @Test
    void periodCommentsContainOnlyCommentsOfPeopleWithoutContacts() {
        UUID command = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO command_idempotency_records (id, actor_profile_id, operation, idempotency_key, request_fingerprint)
                VALUES (?, ?, 'APPLY_SOURCE_RECORD', 'source-1', ?)
                """, command, LEADER_A, "0".repeat(64));
        ReportTestData.event(jdbcTemplate, INTERACTION_TWO_PRODUCTS, "COMMENTED", "Встреча", null,
                "Заявка с сайта: Иванов, ivanov@example.test, +7 900 000-00-00", MANAGER_A, at("2026-09-12T12:00:00+03:00"));
        jdbcTemplate.update("UPDATE interaction_events SET command_id = ? WHERE comment LIKE 'Заявка с сайта%'", command);

        ReportRequest request = new ReportRequest(ReportKind.MANAGER_WORK, SEPTEMBER_FIRST, SEPTEMBER_LAST, null,
                ReportFilters.none(), List.of(ReportColumn.INTERACTION, ReportColumn.PERIOD_COMMENTS), ReportFormat.XLSX, null, null);
        String comments = reportService.preview(LEADER_A_PROFILE, request, 0, 50).items().stream()
                .filter(item -> "Договор на курс Java".equals(item.get("INTERACTION")))
                .map(item -> (String) item.get("PERIOD_COMMENTS")).findFirst().orElseThrow();

        assertThat(comments).contains("05.09.2026: Назначена встреча").doesNotContain("Заявка с сайта")
                .doesNotContain("ivanov@example.test").doesNotContain("+7 900");
    }

    @Test
    void methodNotesStayWithinTwoScreenLines() {
        ReportTestData.insertDemand(jdbcTemplate);
        ReportTestData.trackMarket(jdbcTemplate, ReportTestData.DIRECTION, "programming");
        for (ReportKind kind : ReportKind.values()) {
            if (kind == ReportKind.AGREEMENTS) {
                continue;
            }
            ReportRequest request = kind == ReportKind.SNAPSHOT ? request(kind) : request(kind, SEPTEMBER_FIRST, SEPTEMBER_LAST);
            assertThat(reportService.preview(LEADER_A_PROFILE, request, 0, 10).notes())
                    .as(kind.name()).allSatisfy(note -> assertThat(note.length()).isLessThanOrEqualTo(420));
        }
    }

    private void addMonthlyWork(int index) {
        UUID organization = UUID.randomUUID();
        UUID interaction = UUID.randomUUID();
        UUID stage = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, type, team_id, owner_manager_id) VALUES (?, ?, 'UNIVERSITY', ?, ?)",
                organization, "Вуз-" + index, TEAM_A, MANAGER_A);
        jdbcTemplate.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name) VALUES (?, ?, 0, 'Поиск контакта')",
                stage, interaction);
        OffsetDateTime created = OffsetDateTime.parse("2026-09-0" + (index + 1) + "T10:00:00+03:00");
        jdbcTemplate.update("INSERT INTO interactions (id, organization_id, title, current_stage_id, created_at) VALUES (?, ?, ?, ?, ?)",
                interaction, organization, "Работа " + index, stage, created);
        ReportTestData.event(jdbcTemplate, interaction, "CREATED", "Поиск контакта", null, null, MANAGER_A, created);
    }

    private long portfolioTotal() {
        return reportService.preview(LEADER_A_PROFILE, request(ReportKind.PORTFOLIO), 0, 50).total();
    }

    private long controlWorks() {
        return workControlService.control(LEADER_A_PROFILE, ReportFilters.none(), null, null).total().works();
    }

    private long participants(ReportRequest request) {
        return reportService.document(LEADER_A_PROFILE, request).rows().stream()
                .map(ReportRow::participants).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    private static ReportRequest request(ReportKind kind) {
        return request(kind, null, null);
    }

    private static ReportRequest request(ReportKind kind, LocalDate from, LocalDate to) {
        return new ReportRequest(kind, kind == ReportKind.SNAPSHOT ? null : from, kind == ReportKind.SNAPSHOT ? null : to, null,
                ReportFilters.none(), null, ReportFormat.XLSX, null, null);
    }

    private static ReportRequest managerWork(LocalDate from, LocalDate to) {
        return request(ReportKind.MANAGER_WORK, from, to);
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
