package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_TWO_PRODUCTS;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_UNASSIGNED;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_WITHOUT_LINKS;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A2;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_B;
import static ru.rtk.crm.report.ReportTestData.MANAGER_B_PROFILE;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_B;
import static ru.rtk.crm.report.ReportTestData.PROGRAM;
import static ru.rtk.crm.report.ReportTestData.at;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:report-views;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ReportRepository.class,
        ReportService.class,
        ReportViewsTest.PropertiesConfiguration.class
})
class ReportViewsTest {
    private static final LocalDate AUGUST_FIRST = LocalDate.parse("2026-08-01");
    private static final LocalDate SEPTEMBER_FIRST = LocalDate.parse("2026-09-01");
    private static final LocalDate SEPTEMBER_TWENTIETH = LocalDate.parse("2026-09-20");
    private static final LocalDate SEPTEMBER_LAST = LocalDate.parse("2026-09-30");

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbcTemplate);
        ReportTestData.insertScenario(jdbcTemplate);
    }

    @Test
    void activeInPeriodKeepsOldCardsWithoutEventsAndExcludesCardsCreatedLater() {
        LocalDate from = LocalDate.parse("2026-09-06");
        LocalDate to = LocalDate.parse("2026-09-12");

        assertThat(ids(LEADER_A_PROFILE, portfolio(from, to, PeriodBasis.ACTIVE)))
                .containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS);
        assertThat(ids(LEADER_A_PROFILE, portfolio(from, to, PeriodBasis.CREATED))).isEmpty();
        assertThat(ids(LEADER_A_PROFILE, portfolio(LocalDate.parse("2026-09-11"), to, PeriodBasis.ACTIVITY))).isEmpty();
        assertThat(ids(MANAGER_B_PROFILE, portfolio(from, to, PeriodBasis.ACTIVE))).containsExactly(ReportTestData.INTERACTION_FOREIGN);

        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, portfolio(from, to, PeriodBasis.ACTIVE), 0, 50);
        assertThat(preview.total()).isEqualTo(2);
        assertThat(preview.notes()).anyMatch(note -> note.contains("созданные до конца периода и не завершённые к его началу"));

        assertThatThrownBy(() -> reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.PORTFOLIO,
                StatisticsGroupBy.MONTH, from, to, PeriodBasis.ACTIVE, null, null, null)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("groupBy"));
    }

    @Test
    void snapshotRestoresStageAndOwnerAtChosenDateFromHistory() {
        ReportTestData.assignment(jdbcTemplate, ORGANIZATION_A, MANAGER_A, "Анна Кузнецова", MANAGER_A2, "Борис Смирнов",
                at("2026-09-08T10:00:00+03:00"));
        ReportTestData.assignment(jdbcTemplate, ORGANIZATION_A, MANAGER_A2, "Борис Смирнов", MANAGER_A, "Анна Кузнецова",
                at("2026-09-20T10:00:00+03:00"));

        List<ReportRow> firstOfSeptember = rows(LEADER_A_PROFILE, snapshot(SEPTEMBER_FIRST, filters()));
        assertThat(firstOfSeptember).extracting(ReportRow::interactionId, ReportRow::stageName, ReportRow::managerName)
                .containsExactlyInAnyOrder(
                        tuple(INTERACTION_TWO_PRODUCTS, "Поиск контакта", "Анна Кузнецова"),
                        tuple(INTERACTION_WITHOUT_LINKS, "Поиск контакта", "Анна Кузнецова")
                );

        List<ReportRow> tenthOfSeptember = rows(LEADER_A_PROFILE, snapshot(LocalDate.parse("2026-09-10"), filters()));
        assertThat(tenthOfSeptember).extracting(ReportRow::interactionId, ReportRow::stageName, ReportRow::managerName)
                .containsExactlyInAnyOrder(
                        tuple(INTERACTION_TWO_PRODUCTS, "Встреча", "Борис Смирнов"),
                        tuple(INTERACTION_WITHOUT_LINKS, "Поиск контакта", "Борис Смирнов")
                );
        assertThat(tenthOfSeptember).filteredOn(row -> row.interactionId().equals(INTERACTION_TWO_PRODUCTS))
                .extracting(ReportRow::lastEventAt).containsExactly(at("2026-09-10T12:00:00+03:00"));

        assertThat(rows(LEADER_A_PROFILE, snapshot(ReportTestData.TODAY, filters())))
                .extracting(ReportRow::interactionId, ReportRow::managerName)
                .containsExactlyInAnyOrder(
                        tuple(INTERACTION_TWO_PRODUCTS, "Анна Кузнецова"),
                        tuple(INTERACTION_WITHOUT_LINKS, "Анна Кузнецова"),
                        tuple(INTERACTION_UNASSIGNED, null)
                );
        assertThat(ids(LEADER_A_PROFILE, snapshot(LocalDate.parse("2026-09-10"),
                filters(List.of(MANAGER_A2), List.of(), List.of()))))
                .containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS);
        assertThat(ids(LEADER_A_PROFILE, snapshot(LocalDate.parse("2026-09-10"),
                filters(List.of(), List.of("Встреча"), List.of()))))
                .containsExactly(INTERACTION_TWO_PRODUCTS);
        assertThat(ids(MANAGER_B_PROFILE, snapshot(ReportTestData.TODAY, filters()))).doesNotContain(INTERACTION_TWO_PRODUCTS);

        StatisticsResult byStage = reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.SNAPSHOT,
                StatisticsGroupBy.STAGE, null, null, null, filters(), LocalDate.parse("2026-09-10"), null));
        assertThat(byStage.total()).isEqualTo(2);
        assertThat(byStage.items()).extracting(StatisticsResult.Item::label, StatisticsResult.Item::count)
                .containsExactlyInAnyOrder(tuple("Встреча", 1L), tuple("Поиск контакта", 1L));

        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, snapshot(SEPTEMBER_FIRST, filters()), 0, 50);
        assertThat(preview.columns()).extracting(ReportColumnView::title).contains("Этап на дату", "Ответственный на дату");
        assertThat(preview.notes()).anyMatch(note -> note.startsWith("Состояние на 01.09.2026"));

        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, snapshot(LocalDate.now(ReportRequest.ZONE).plusDays(1), filters())))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("asOf"));
        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, new ReportRequest(ReportKind.SNAPSHOT, SEPTEMBER_FIRST, null, null,
                filters(), null, null, null, null, null, null, null)))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, new ReportRequest(ReportKind.PORTFOLIO, null, null, null,
                filters(), null, null, null, null, SEPTEMBER_FIRST, null, null)))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void assignmentsAreEventsOfThePeriodVisibleOnlyToTheLeaderOfTheTeam() {
        ReportTestData.assignment(jdbcTemplate, ORGANIZATION_A, MANAGER_A2, "Борис Смирнов", MANAGER_A, "Анна Кузнецова",
                at("2026-09-12T10:00:00+03:00"));
        ReportTestData.assignment(jdbcTemplate, ORGANIZATION_B, null, null, MANAGER_B, "Вера Орлова",
                at("2026-09-12T11:00:00+03:00"));

        List<ReportRow> events = rows(LEADER_A_PROFILE, events(filters()));
        assertThat(events).filteredOn(row -> row.interactionId() == null)
                .extracting(ReportRow::organizationName, ReportRow::eventType, ReportRow::comment, ReportRow::authorName,
                        ReportRow::managerName)
                .containsExactly(tuple("Университет «Альфа»", "Смена КАМ", "Борис Смирнов → Анна Кузнецова",
                        "Галина Лебедева", "Анна Кузнецова"));
        assertThat(events).extracting(ReportRow::eventAt).isSorted();
        assertThat(reportService.preview(LEADER_A_PROFILE, events(filters()), 0, 50).total()).isEqualTo(events.size());

        assertThat(rows(LEADER_A_PROFILE, events(filters(List.of(), List.of(), List.of(ReportEventType.ASSIGNMENT)))))
                .extracting(ReportRow::eventType).containsExactly("Смена КАМ");
        assertThat(rows(LEADER_A_PROFILE, events(filters(List.of(), List.of(), List.of(ReportEventType.COMMENTED)))))
                .extracting(ReportRow::eventType).containsOnly("Комментарий");
        assertThat(rows(LEADER_A_PROFILE, events(new ReportFilters(List.of(), List.of(), false, List.of(PROGRAM), false,
                List.of(), false, List.of(), false, List.of(), null, List.of(), List.of(), ReportAgreementFilters.none(), List.of(), null))))
                .noneMatch(row -> row.interactionId() == null);
        assertThat(rows(LEADER_A_PROFILE, events(new ReportFilters(List.of(), List.of(), false, List.of(PROGRAM), true,
                List.of(), false, List.of(), false, List.of(), null, List.of(), List.of(), ReportAgreementFilters.none(), List.of(), null))))
                .anyMatch(row -> row.interactionId() == null);
        List<ReportRow> managerEvents = rows(MANAGER_A_PROFILE, events(filters()));
        assertThat(managerEvents).isNotEmpty().noneMatch(row -> row.interactionId() == null);
        assertThat(reportService.preview(MANAGER_A_PROFILE, events(filters()), 0, 50).total()).isEqualTo(managerEvents.size());
        assertThat(rows(MANAGER_A_PROFILE, events(filters(List.of(), List.of(), List.of(ReportEventType.ASSIGNMENT))))).isEmpty();
        assertThat(rows(MANAGER_B_PROFILE, events(filters()))).noneMatch(row -> row.interactionId() == null);
        StatisticsResult managerStatistics = reportService.statistics(MANAGER_A_PROFILE, new StatisticsRequest(ReportKind.EVENTS,
                StatisticsGroupBy.MANAGER, SEPTEMBER_FIRST, SEPTEMBER_LAST, null, filters(), null, null));
        assertThat(managerStatistics.total()).isEqualTo(managerEvents.size());
        assertThat(managerStatistics.items().stream().mapToLong(StatisticsResult.Item::count).sum() + managerStatistics.unknownCount())
                .isEqualTo(managerEvents.size());
        assertThat(reportService.preview(MANAGER_A_PROFILE, events(filters()), 0, 50).notes())
                .anyMatch(note -> note.contains("история назначений КАМ в отчёт КАМ не входит"));

        StatisticsResult byStage = reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.EVENTS,
                StatisticsGroupBy.STAGE, SEPTEMBER_FIRST, SEPTEMBER_LAST, null, filters(), null, null));
        assertThat(byStage.total()).isEqualTo(events.size());
        assertThat(byStage.unknownCount()).isEqualTo(1);
        assertThat(byStage.items().stream().mapToLong(StatisticsResult.Item::count).sum() + byStage.unknownCount())
                .isEqualTo(byStage.total());

        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, portfolio(null, null, null,
                filters(List.of(), List.of(), List.of(ReportEventType.ASSIGNMENT)))))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("filters.eventTypes"));
    }

    @Test
    void portfolioShowsDaysOnCurrentStageAndSelectsCardsStuckLongerThanNDays() {
        OffsetDateTime before = OffsetDateTime.now();
        List<ReportRow> rows = rows(LEADER_A_PROFILE, portfolio(null, null, null));
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(daysOnStage(rows, INTERACTION_TWO_PRODUCTS))
                .isBetween(days(at("2026-09-05T12:00:00+03:00"), before), days(at("2026-09-05T12:00:00+03:00"), after));
        assertThat(daysOnStage(rows, INTERACTION_WITHOUT_LINKS))
                .isBetween(days(at("2026-09-01T00:00:00+03:00"), before), days(at("2026-09-01T00:00:00+03:00"), after));
        assertThat(daysOnStage(rows, INTERACTION_UNASSIGNED))
                .isBetween(days(at("2026-09-15T09:00:00+03:00"), before), days(at("2026-09-15T09:00:00+03:00"), after));

        int stuck = Math.toIntExact(days(at("2026-09-10T00:00:00+03:00"), OffsetDateTime.now()));
        ReportRequest longer = portfolio(null, null, null, new ReportFilters(List.of(), List.of(), false, List.of(), false,
                List.of(), false, List.of(), false, List.of(), null, List.of(), List.of(), ReportAgreementFilters.none(), List.of(), stuck));
        assertThat(ids(LEADER_A_PROFILE, longer)).containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS);
        assertThat(ids(MANAGER_B_PROFILE, longer)).doesNotContain(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS);
        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, longer, 0, 50);
        assertThat(preview.total()).isEqualTo(2);
        assertThat(preview.columns()).extracting(ReportColumnView::title).contains("Дней на этапе");
        assertThat(preview.notes()).anyMatch(note -> note.contains("на этапе дольше " + stuck + " дн."));
        assertThat(reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.PORTFOLIO, StatisticsGroupBy.STAGE,
                null, null, null, longer.filters(), null, null)).total()).isEqualTo(2);

        ReportFilters zero = new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, List.of(), false,
                List.of(), null, List.of(), List.of(), ReportAgreementFilters.none(), List.of(), 0);
        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, portfolio(null, null, null, zero)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("filters.minDaysOnStage"));
        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, events(longer.filters())))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("filters.minDaysOnStage"));
    }

    @Test
    void durationReportAveragesCompletedStagesAndCyclesByTeamAndProgram() {
        ReportTestData.event(jdbcTemplate, INTERACTION_WITHOUT_LINKS, "TRANSITIONED", "Встреча", "Поиск контакта", "После конца периода",
                MANAGER_A, at("2026-09-25T10:00:00+03:00"));
        List<ReportRow> rows = rows(LEADER_A_PROFILE, duration(SEPTEMBER_FIRST, SEPTEMBER_TWENTIETH, List.of()));

        assertThat(rows).extracting(ReportRow::teamName, ReportRow::programName, ReportRow::stageName,
                        ReportRow::completedCount, ReportRow::averageDays, ReportRow::maxDays, ReportRow::currentCount,
                        ReportRow::currentMaxDays)
                .containsExactly(
                        tuple("Команда А", "Все программы", "Весь цикл", 1L, days("26.1"), days("26.1"), 2L, days("20.0")),
                        tuple("Команда А", "Все программы", "Поиск контакта", 1L, days("26.1"), days("26.1"), 2L, days("20.0")),
                        tuple("Команда А", "Все программы", "Встреча", 0L, null, null, 1L, days("15.5")),
                        tuple("Команда А", "Java-разработчик", "Весь цикл", 1L, days("26.1"), days("26.1"), 1L, days("5.6")),
                        tuple("Команда А", "Java-разработчик", "Поиск контакта", 1L, days("26.1"), days("26.1"), 1L, days("5.6")),
                        tuple("Команда А", "Java-разработчик", "Встреча", 0L, null, null, 1L, days("15.5")),
                        tuple("Команда А", null, "Весь цикл", 0L, null, null, 1L, days("20.0")),
                        tuple("Команда А", null, "Поиск контакта", 0L, null, null, 1L, days("20.0"))
                );
        assertThat(ReportColumn.AVG_DAYS.text(rows.getFirst())).isEqualTo("26,1");
        assertThat(ReportColumn.PROGRAM.text(rows.getLast())).isEqualTo("Не указано");
        assertThat(ReportColumn.AVG_DAYS.text(rows.getLast())).isEqualTo("нет данных");

        List<ReportRow> afterCompletion = rows(LEADER_A_PROFILE, duration(LocalDate.parse("2026-09-06"), SEPTEMBER_TWENTIETH, List.of()));
        assertThat(afterCompletion.getFirst()).extracting(ReportRow::stageName, ReportRow::completedCount, ReportRow::currentCount)
                .containsExactly("Весь цикл", 0L, 2L);

        assertThat(rows(LEADER_A_PROFILE, duration(SEPTEMBER_FIRST, SEPTEMBER_TWENTIETH, List.of("Встреча"))))
                .extracting(ReportRow::stageName).containsOnly("Весь цикл", "Встреча");
        assertThat(rows(MANAGER_A_PROFILE, duration(SEPTEMBER_FIRST, SEPTEMBER_TWENTIETH, List.of())))
                .filteredOn(row -> row.programName() != null && row.programName().equals("Java-разработчик"))
                .extracting(ReportRow::stageName, ReportRow::currentCount)
                .containsExactly(tuple("Весь цикл", 0L), tuple("Поиск контакта", 0L), tuple("Встреча", 1L));
        assertThat(rows(MANAGER_B_PROFILE, duration(SEPTEMBER_FIRST, SEPTEMBER_TWENTIETH, List.of())))
                .extracting(ReportRow::teamName).containsOnly("Команда Б");

        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, duration(SEPTEMBER_FIRST, SEPTEMBER_TWENTIETH, List.of()), 1, 5);
        assertThat(preview.total()).isEqualTo(8);
        assertThat(preview.items()).hasSize(3);
        assertThat(preview.notes()).anyMatch(note -> note.contains("21.09.2026 00:00"));

        assertThatThrownBy(() -> reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.DURATION,
                StatisticsGroupBy.STAGE, SEPTEMBER_FIRST, SEPTEMBER_TWENTIETH, null, filters(), null, null)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("groupBy"));
    }

    @Test
    void monthlyLinesSplitCountsBySeriesAndKeepUnspecifiedSeparate() {
        StatisticsResult result = reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.EVENTS,
                StatisticsGroupBy.MONTH, AUGUST_FIRST, LocalDate.parse("2026-10-31"), null, filters(), null, StatisticsGroupBy.PROGRAM));

        assertThat(result.items()).extracting(StatisticsResult.Item::key, StatisticsResult.Item::count)
                .containsExactly(tuple("2026-08", 2L), tuple("2026-09", 4L), tuple("2026-10", 1L));
        assertThat(result.series()).extracting(StatisticsResult.Series::label, StatisticsResult.Series::unspecified,
                        StatisticsResult.Series::counts)
                .containsExactly(
                        tuple("Java-разработчик", false, List.of(2L, 3L, 1L)),
                        tuple("Не указано", true, List.of(0L, 1L, 0L))
                );
        assertThat(result.maxChartSeries()).isEqualTo(StatisticsResult.MAX_CHART_SERIES);

        assertThatThrownBy(() -> reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.EVENTS,
                StatisticsGroupBy.STAGE, null, null, null, filters(), null, StatisticsGroupBy.PROGRAM)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("seriesBy"));
        assertThatThrownBy(() -> reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(ReportKind.DEMAND,
                StatisticsGroupBy.MONTH, null, null, null, filters(), null, StatisticsGroupBy.STAGE)))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("seriesBy"));
        assertThatThrownBy(() -> new ReportRequest(ReportKind.EVENTS, null, null, null, filters(), null, ReportFormat.PNG,
                StatisticsGroupBy.MONTH, null, null, ChartType.BAR, StatisticsGroupBy.PROGRAM).normalized())
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> new ReportRequest(ReportKind.EVENTS, null, null, null, filters(), null, ReportFormat.PNG,
                StatisticsGroupBy.PROGRAM, null, null, ChartType.LINE, null).normalized())
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("chartType"));
    }

    private static BigDecimal days(String value) {
        return new BigDecimal(value);
    }

    private static long days(OffsetDateTime from, OffsetDateTime to) {
        return Duration.between(from, to).toDays();
    }

    private static Long daysOnStage(List<ReportRow> rows, UUID interactionId) {
        return rows.stream().filter(row -> row.interactionId().equals(interactionId)).findFirst().orElseThrow().daysOnStage();
    }

    private List<UUID> ids(CrmProfile profile, ReportRequest request) {
        return rows(profile, request).stream().map(ReportRow::interactionId).toList();
    }

    private List<ReportRow> rows(CrmProfile profile, ReportRequest request) {
        return reportService.document(profile, request).rows();
    }

    private static ReportRequest portfolio(LocalDate from, LocalDate to, PeriodBasis basis) {
        return portfolio(from, to, basis, filters());
    }

    private static ReportRequest portfolio(LocalDate from, LocalDate to, PeriodBasis basis, ReportFilters filters) {
        return new ReportRequest(ReportKind.PORTFOLIO, from, to, basis, filters, null, null, null, null, null, null, null);
    }

    private static ReportRequest snapshot(LocalDate asOf, ReportFilters filters) {
        return new ReportRequest(ReportKind.SNAPSHOT, null, null, null, filters, null, null, null, null, asOf, null, null);
    }

    private static ReportRequest events(ReportFilters filters) {
        return new ReportRequest(ReportKind.EVENTS, SEPTEMBER_FIRST, SEPTEMBER_LAST, null, filters, null, null, null, null,
                null, null, null);
    }

    private static ReportRequest duration(LocalDate from, LocalDate to, List<String> stages) {
        return new ReportRequest(ReportKind.DURATION, from, to, null, filters(List.of(), stages, List.of()), null, null, null,
                null, null, null, null);
    }

    private static ReportFilters filters() {
        return ReportFilters.none();
    }

    private static ReportFilters filters(List<UUID> managers, List<String> stages, List<ReportEventType> eventTypes) {
        return new ReportFilters(List.of(), List.of(), false, List.of(), false, List.of(), false, managers, false, stages,
                null, List.of(), List.of(), ReportAgreementFilters.none(), eventTypes, null);
    }

    @TestConfiguration
    static class PropertiesConfiguration {
        @Bean
        ReportProperties reportProperties() {
            return new ReportProperties(Path.of("target", "test-reports"), 10, 5, 1_000, 1_000);
        }
    }
}
