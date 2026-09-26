package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.rtk.crm.report.ReportTestData.ADMIN_PROFILE;
import static ru.rtk.crm.report.ReportTestData.DIRECTION;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_FOREIGN;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_TWO_PRODUCTS;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_UNASSIGNED;
import static ru.rtk.crm.report.ReportTestData.INTERACTION_WITHOUT_LINKS;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A2;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_B_PROFILE;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_B;
import static ru.rtk.crm.report.ReportTestData.PRODUCT_X;
import static ru.rtk.crm.report.ReportTestData.PRODUCT_Y;
import static ru.rtk.crm.report.ReportTestData.PROGRAM;

import java.nio.file.Path;
import java.time.LocalDate;
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
        "spring.datasource.url=jdbc:h2:mem:reports;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ReportRepository.class,
        ReportService.class,
        ReportServiceTest.PropertiesConfiguration.class
})
class ReportServiceTest {
    private static final LocalDate SEPTEMBER_FIRST = LocalDate.parse("2026-09-01");
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
    void managerSeesOnlyOwnOrganizationInReportStatisticsAndFilters() {
        assertThat(interactionIds(MANAGER_A_PROFILE, portfolio(filters()))).containsExactly(
                INTERACTION_TWO_PRODUCTS,
                INTERACTION_WITHOUT_LINKS
        );
        assertThat(interactionIds(MANAGER_B_PROFILE, portfolio(filters()))).containsExactly(INTERACTION_FOREIGN);
        assertThat(interactionIds(MANAGER_A_PROFILE, portfolio(filters().organizations(ORGANIZATION_B)))).isEmpty();
        assertThat(interactionIds(MANAGER_A_PROFILE, events(null, null, filters())))
                .doesNotContain(INTERACTION_FOREIGN);

        StatisticsResult statistics = reportService.statistics(
                MANAGER_A_PROFILE,
                statistics(ReportKind.PORTFOLIO, StatisticsGroupBy.ORGANIZATION, null, null, filters())
        );
        assertThat(statistics.total()).isEqualTo(2);
        assertThat(statistics.items()).extracting(StatisticsResult.Item::key).containsExactly(ORGANIZATION_A.toString());
        assertThat(reportService.managers(MANAGER_A_PROFILE)).extracting(ReportManagerOption::id).containsExactly(MANAGER_A);
    }

    @Test
    void leaderSeesOwnTeamIncludingUnassignedOrganizationButNotOtherTeam() {
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters()))).containsExactlyInAnyOrder(
                INTERACTION_TWO_PRODUCTS,
                INTERACTION_WITHOUT_LINKS,
                INTERACTION_UNASSIGNED
        );
        assertThat(reportService.managers(LEADER_A_PROFILE)).extracting(ReportManagerOption::id)
                .containsExactlyInAnyOrder(MANAGER_A, MANAGER_A2);
        assertThat(reportService.stages(LEADER_A_PROFILE))
                .containsExactly("Поиск контакта", "Уточнение актуальности", "Встреча");
        assertThat(interactionIds(ADMIN_PROFILE, portfolio(filters()))).isEmpty();
    }

    @Test
    void twoProductsDoNotDuplicateInteractionRowOrCounters() {
        ReportRequest request = portfolio(filters().products(PRODUCT_X, PRODUCT_Y));

        List<ReportRow> rows = rows(MANAGER_A_PROFILE, request);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().productNames()).isEqualTo("Продукт Игрек, Продукт Икс");
        assertThat(reportService.preview(MANAGER_A_PROFILE, request, 0, 50).total()).isEqualTo(1);

        StatisticsResult byStage = reportService.statistics(
                MANAGER_A_PROFILE,
                statistics(ReportKind.PORTFOLIO, StatisticsGroupBy.STAGE, null, null, filters())
        );
        assertThat(byStage.total()).isEqualTo(2);
        assertThat(byStage.items()).extracting(StatisticsResult.Item::count).containsExactly(1L, 1L);

        StatisticsResult byProduct = reportService.statistics(
                MANAGER_A_PROFILE,
                statistics(ReportKind.PORTFOLIO, StatisticsGroupBy.PRODUCT, null, null, filters())
        );
        assertThat(byProduct.total()).isEqualTo(2);
        assertThat(byProduct.unknownCount()).isEqualTo(1);
        assertThat(byProduct.items()).extracting(StatisticsResult.Item::label, StatisticsResult.Item::count)
                .containsExactly(
                        tuple("Продукт Игрек", 1L),
                        tuple("Продукт Икс", 1L)
                );

        StatisticsResult eventsByProduct = reportService.statistics(
                LEADER_A_PROFILE,
                statistics(ReportKind.EVENTS, StatisticsGroupBy.PRODUCT, null, null, filters())
        );
        assertThat(eventsByProduct.total()).isEqualTo(7);
        assertThat(eventsByProduct.unknownCount()).isEqualTo(1);
        assertThat(eventsByProduct.items()).extracting(StatisticsResult.Item::key, StatisticsResult.Item::count)
                .containsExactly(
                        tuple(PRODUCT_X.toString(), 6L),
                        tuple(PRODUCT_Y.toString(), 5L)
                );
        assertThat(eventsByProduct.maxChartBars()).isEqualTo(StatisticsResult.MAX_CHART_BARS);
    }

    @Test
    void unspecifiedValuesAreSelectedExplicitlyAndNeverDroppedWithoutFilter() {
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().programs(PROGRAM))))
                .containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_UNASSIGNED);
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().noProgram())))
                .containsExactly(INTERACTION_WITHOUT_LINKS);
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().programs(PROGRAM).noProgram())))
                .hasSize(3);
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().directions(DIRECTION).noDirection())))
                .hasSize(3);
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().noManager())))
                .containsExactly(INTERACTION_UNASSIGNED);
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().noProduct())))
                .containsExactly(INTERACTION_WITHOUT_LINKS);

        ReportRow withoutLinks = rows(LEADER_A_PROFILE, portfolio(filters().noProgram())).getFirst();
        assertThat(ReportColumn.PROGRAM.text(withoutLinks)).isEqualTo("Не указано");
        assertThat(ReportColumn.PRODUCTS.text(withoutLinks)).isEqualTo("Не указано");

        StatisticsResult byProgram = reportService.statistics(
                LEADER_A_PROFILE,
                statistics(ReportKind.PORTFOLIO, StatisticsGroupBy.PROGRAM, null, null, filters())
        );
        assertThat(byProgram.total()).isEqualTo(3);
        assertThat(byProgram.unknownCount()).isEqualTo(1);
        assertThat(byProgram.items()).extracting(StatisticsResult.Item::count).containsExactly(2L);
    }

    @Test
    void periodUsesMoscowDayBoundariesAndSelectedBasis() {
        ReportRequest created = new ReportRequest(ReportKind.PORTFOLIO, SEPTEMBER_FIRST, SEPTEMBER_LAST,
                PeriodBasis.CREATED, filters().build(), null, null, null, null);
        assertThat(interactionIds(LEADER_A_PROFILE, created))
                .containsExactlyInAnyOrder(INTERACTION_WITHOUT_LINKS, INTERACTION_UNASSIGNED);

        ReportRequest activity = new ReportRequest(ReportKind.PORTFOLIO, SEPTEMBER_FIRST, SEPTEMBER_LAST,
                PeriodBasis.ACTIVITY, filters().build(), null, null, null, null);
        assertThat(interactionIds(LEADER_A_PROFILE, activity))
                .containsExactlyInAnyOrder(INTERACTION_TWO_PRODUCTS, INTERACTION_WITHOUT_LINKS, INTERACTION_UNASSIGNED);

        List<ReportRow> events = rows(LEADER_A_PROFILE, events(SEPTEMBER_FIRST, SEPTEMBER_LAST, filters()));
        assertThat(events).extracting(ReportRow::comment)
                .containsExactly(null, "Назначена встреча", ReportTestData.FORMULA_COMMENT, null);
        assertThat(events).extracting(ReportRow::eventType)
                .containsExactly("Создание", "Переход", "Комментарий", "Создание");
    }

    @Test
    void eventsFilterManagerBySnapshotWhilePortfolioUsesCurrentOwner() {
        assertThat(interactionIds(LEADER_A_PROFILE, events(null, null, filters().managers(MANAGER_A2))))
                .containsExactly(INTERACTION_TWO_PRODUCTS);
        assertThat(rows(LEADER_A_PROFILE, events(null, null, filters().managers(MANAGER_A2))).getFirst().managerName())
                .isEqualTo("Борис Смирнов");
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().managers(MANAGER_A2)))).isEmpty();
        assertThat(interactionIds(LEADER_A_PROFILE, portfolio(filters().stages("Встреча"))))
                .containsExactly(INTERACTION_TWO_PRODUCTS);
    }

    @Test
    void monthStatisticsFillsZeroMonthsSeparatelyFromUnknown() {
        ReportTestData.event(jdbcTemplate, INTERACTION_WITHOUT_LINKS, "COMMENTED", "Поиск контакта", null,
                "Полночь по Москве, вечер по UTC", MANAGER_A, ReportTestData.at("2026-09-30T21:30:00Z"));

        StatisticsResult byMonth = reportService.statistics(
                LEADER_A_PROFILE,
                statistics(ReportKind.EVENTS, StatisticsGroupBy.MONTH, LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-11-30"), filters())
        );

        assertThat(byMonth.items()).extracting(StatisticsResult.Item::key, StatisticsResult.Item::count).containsExactly(
                tuple("2026-08", 2L),
                tuple("2026-09", 4L),
                tuple("2026-10", 2L),
                tuple("2026-11", 0L)
        );
        assertThat(byMonth.items().getFirst().label()).isEqualTo("август 2026");
        assertThat(byMonth.total()).isEqualTo(8);
        assertThat(byMonth.unknownCount()).isZero();
        assertThat(byMonth.unit()).isEqualTo("события");
    }

    @Test
    void monthGroupingIsRejectedForPortfolioSelectedByActivity() {
        StatisticsRequest activityByMonth = new StatisticsRequest(ReportKind.PORTFOLIO, StatisticsGroupBy.MONTH,
                SEPTEMBER_FIRST, SEPTEMBER_LAST, PeriodBasis.ACTIVITY, filters().build());

        assertThatThrownBy(() -> reportService.statistics(LEADER_A_PROFILE, activityByMonth))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.getMessage()).contains("по месяцам"));

        StatisticsResult createdByMonth = reportService.statistics(LEADER_A_PROFILE, new StatisticsRequest(
                ReportKind.PORTFOLIO, StatisticsGroupBy.MONTH, SEPTEMBER_FIRST, SEPTEMBER_LAST, PeriodBasis.CREATED,
                filters().build()));
        assertThat(createdByMonth.items()).extracting(StatisticsResult.Item::key, StatisticsResult.Item::count)
                .containsExactly(tuple("2026-09", 2L));
    }

    @Test
    void rejectsInvalidPeriodAndForeignColumns() {
        assertThatThrownBy(() -> reportService.preview(
                MANAGER_A_PROFILE,
                new ReportRequest(ReportKind.EVENTS, SEPTEMBER_LAST, SEPTEMBER_FIRST, null, null, null, null, null, null),
                0,
                50
        )).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> reportService.preview(
                MANAGER_A_PROFILE,
                new ReportRequest(ReportKind.PORTFOLIO, null, null, null, null, List.of(ReportColumn.COMMENT), null, null, null),
                0,
                50
        )).isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void demandRanksProgramsBySiteApplicationsAndMoodleSnapshotsInScopeWithoutZeroForMissingData() {
        ReportTestData.insertDemand(jdbcTemplate);

        assertThat(rows(LEADER_A_PROFILE, demand(null, null, filters(), null)))
                .extracting(ReportRow::programName, ReportRow::applications, ReportRow::participants, ReportRow::parallelRuns)
                .containsExactly(
                        tuple("Java-разработчик", 5L, 8L, 2L),
                        tuple(null, 4L, null, null),
                        tuple("Анализ данных", null, 3L, 0L));
        assertThat(rows(LEADER_A_PROFILE, demand(null, ReportTestData.RUN_ENDS.minusDays(1), filters(), null)))
                .extracting(ReportRow::programName, ReportRow::parallelRuns)
                .containsExactlyInAnyOrder(tuple("Java-разработчик", 2L), tuple(null, null), tuple("Анализ данных", 1L));
        assertThat(rows(LEADER_A_PROFILE, demand(null, ReportTestData.RUN_ENDS, filters(), null)))
                .extracting(ReportRow::programName, ReportRow::parallelRuns)
                .containsExactlyInAnyOrder(tuple("Java-разработчик", 0L), tuple(null, null), tuple("Анализ данных", 1L));
        assertThat(rows(LEADER_A_PROFILE, demand(null, ReportTestData.TODAY.minusDays(31), filters(), null)))
                .extracting(ReportRow::programName, ReportRow::participants, ReportRow::parallelRuns)
                .contains(tuple("Java-разработчик", 8L, 0L), tuple("Анализ данных", 3L, 0L));
        assertThat(rows(MANAGER_A_PROFILE, demand(null, null, filters(), null)))
                .extracting(ReportRow::programName, ReportRow::applications)
                .containsExactly(tuple(null, 4L), tuple("Java-разработчик", 3L));
        assertThat(rows(MANAGER_A_PROFILE, demand(null, null, filters(), ReportColumn.PARTICIPANTS)))
                .extracting(ReportRow::programName, ReportRow::participants)
                .containsExactly(tuple("Java-разработчик", 8L), tuple(null, null));
        assertThat(rows(LEADER_A_PROFILE, demand(null, null, filters(), ReportColumn.PARALLEL_RUNS)))
                .extracting(ReportRow::programName)
                .containsExactly("Java-разработчик", "Анализ данных", null);
        assertThat(rows(MANAGER_B_PROFILE, demand(null, null, filters(), null)))
                .extracting(ReportRow::applications, ReportRow::participants)
                .containsExactly(tuple(7L, 4L));
        assertThat(rows(ADMIN_PROFILE, demand(null, null, filters(), null))).isEmpty();
        assertThat(rows(LEADER_A_PROFILE, demand(SEPTEMBER_FIRST, SEPTEMBER_LAST, filters(), null)))
                .extracting(ReportRow::applications, ReportRow::participants)
                .containsExactly(tuple(4L, null), tuple(3L, 8L), tuple(null, 3L));
        assertThat(rows(LEADER_A_PROFILE, demand(null, null, filters().programs(PROGRAM), null)))
                .extracting(ReportRow::applications, ReportRow::participants)
                .containsExactly(tuple(5L, 8L));

        ReportPreview preview = reportService.preview(LEADER_A_PROFILE, demand(null, null, filters(), null), 0, 50);
        assertThat(preview.total()).isEqualTo(3);
        assertThat(preview.columns()).extracting(ReportColumnView::id, ReportColumnView::emptyText).containsExactly(
                tuple(ReportColumn.DIRECTION, ReportColumn.UNSPECIFIED),
                tuple(ReportColumn.PROGRAM, ReportColumn.UNSPECIFIED),
                tuple(ReportColumn.APPLICATIONS, ReportColumn.NO_DATA),
                tuple(ReportColumn.PARTICIPANTS, ReportColumn.NO_DATA),
                tuple(ReportColumn.PARALLEL_RUNS, ReportColumn.NO_DATA)
        );
        assertThat(preview.notes()).anyMatch(note -> note.contains("нет данных") && note.contains("сортировка: заявки"));

        StatisticsResult byProgram = reportService.statistics(
                LEADER_A_PROFILE, statistics(ReportKind.DEMAND, StatisticsGroupBy.PROGRAM, null, null, filters()));
        assertThat(byProgram.total()).isEqualTo(9);
        assertThat(byProgram.unknownCount()).isEqualTo(4);
        assertThat(byProgram.items()).extracting(StatisticsResult.Item::label, StatisticsResult.Item::count)
                .containsExactly(tuple("Java-разработчик", 5L));
        StatisticsResult byMonth = reportService.statistics(LEADER_A_PROFILE, statistics(
                ReportKind.DEMAND, StatisticsGroupBy.MONTH, LocalDate.parse("2026-08-01"), SEPTEMBER_LAST, filters()));
        assertThat(byMonth.items()).extracting(StatisticsResult.Item::key, StatisticsResult.Item::count)
                .containsExactly(tuple("2026-08", 2L), tuple("2026-09", 7L));

        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, demand(null, null, filters().products(PRODUCT_X), null)))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, demand(null, null, filters().stages("Встреча"), null)))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> reportService.statistics(
                LEADER_A_PROFILE, statistics(ReportKind.DEMAND, StatisticsGroupBy.STAGE, null, null, filters())))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> rows(LEADER_A_PROFILE, new ReportRequest(ReportKind.PORTFOLIO, null, null, null,
                filters().build(), null, null, null, ReportColumn.APPLICATIONS)))
                .isInstanceOf(InteractionValidationException.class);
    }

    private static ReportRequest demand(LocalDate from, LocalDate to, Filters filters, ReportColumn sortBy) {
        return new ReportRequest(ReportKind.DEMAND, from, to, null, filters.build(), null, null, null, sortBy);
    }

    private List<UUID> interactionIds(CrmProfile profile, ReportRequest request) {
        return rows(profile, request).stream().map(ReportRow::interactionId).distinct().toList();
    }

    private List<ReportRow> rows(CrmProfile profile, ReportRequest request) {
        return reportService.document(profile, request).rows();
    }

    private static ReportRequest portfolio(Filters filters) {
        return new ReportRequest(ReportKind.PORTFOLIO, null, null, null, filters.build(), null, null, null, null);
    }

    private static ReportRequest events(LocalDate from, LocalDate to, Filters filters) {
        return new ReportRequest(ReportKind.EVENTS, from, to, null, filters.build(), null, null, null, null);
    }

    private static StatisticsRequest statistics(
            ReportKind kind,
            StatisticsGroupBy groupBy,
            LocalDate from,
            LocalDate to,
            Filters filters
    ) {
        return new StatisticsRequest(kind, groupBy, from, to, null, filters.build());
    }

    private static Filters filters() {
        return new Filters();
    }

    private static final class Filters {
        private List<UUID> organizations = List.of();
        private List<UUID> directions = List.of();
        private boolean noDirection;
        private List<UUID> programs = List.of();
        private boolean noProgram;
        private List<UUID> products = List.of();
        private boolean noProduct;
        private List<UUID> managers = List.of();
        private boolean noManager;
        private List<String> stages = List.of();

        Filters organizations(UUID... ids) {
            organizations = List.of(ids);
            return this;
        }

        Filters directions(UUID... ids) {
            directions = List.of(ids);
            return this;
        }

        Filters noDirection() {
            noDirection = true;
            return this;
        }

        Filters programs(UUID... ids) {
            programs = List.of(ids);
            return this;
        }

        Filters noProgram() {
            noProgram = true;
            return this;
        }

        Filters products(UUID... ids) {
            products = List.of(ids);
            return this;
        }

        Filters noProduct() {
            noProduct = true;
            return this;
        }

        Filters managers(UUID... ids) {
            managers = List.of(ids);
            return this;
        }

        Filters noManager() {
            noManager = true;
            return this;
        }

        Filters stages(String... names) {
            stages = List.of(names);
            return this;
        }

        ReportFilters build() {
            return new ReportFilters(organizations, directions, noDirection, programs, noProgram, products, noProduct,
                    managers, noManager, stages, null);
        }
    }

    @TestConfiguration
    static class PropertiesConfiguration {
        @Bean
        ReportProperties reportProperties() {
            return new ReportProperties(Path.of("target", "test-reports"), 10, 5, 1_000, 1_000);
        }
    }
}
