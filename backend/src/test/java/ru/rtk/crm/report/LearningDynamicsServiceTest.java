package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.rtk.crm.report.ReportTestData.ADMIN_PROFILE;
import static ru.rtk.crm.report.ReportTestData.DIRECTION;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_B_PROFILE;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A_UNASSIGNED;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_B;
import static ru.rtk.crm.report.ReportTestData.PROGRAM;
import static ru.rtk.crm.report.ReportTestData.PROGRAM_DATA;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.LearningDynamics.SeriesBy;
import ru.rtk.crm.work.LearningTrend;
import ru.rtk.crm.work.LearningTrendService;
import ru.rtk.crm.work.WorkAccessDeniedException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:learning-dynamics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ReportRepository.class,
        LearningHistoryRepository.class,
        LearningDynamicsService.class,
        LearningTrendService.class
})
class LearningDynamicsServiceTest {
    private static final LocalDate FROM = LocalDate.parse("2026-01-01");
    private static final LocalDate TO = LocalDate.parse("2026-06-30");

    @Autowired
    private LearningDynamicsService service;

    @Autowired
    private LearningTrendService trendService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbcTemplate);
        ReportTestData.insertScenario(jdbcTemplate);
        jdbcTemplate.update("INSERT INTO programs (id, direction_id, name) VALUES (?, ?, ?)", PROGRAM_DATA, DIRECTION, "Анализ данных");
        UUID javaRun = run(ORGANIZATION_A, PROGRAM, "2026-02-01", "2026-06-01", "STUDENTS");
        observe(javaRun, "2026-01-25", 10, 0);
        observe(javaRun, "2026-03-10", 12, 2);
        observe(javaRun, "2026-05-20", 12, 9);
        UUID dataRun = run(ORGANIZATION_B, PROGRAM_DATA, "2026-03-01", "2026-12-31", "STUDENTS");
        observe(dataRun, "2026-03-05", 8, null);
        observe(dataRun, "2026-04-15", 5, null);
        UUID lateRun = run(ORGANIZATION_A_UNASSIGNED, PROGRAM, "2026-04-01", "2026-12-31", "STUDENTS");
        observe(lateRun, "2026-06-10", 7, null);
        observe(run(ORGANIZATION_A, PROGRAM, "2026-02-01", "2026-12-31", "TEACHERS"), "2026-02-10", 30, 30);
    }

    @Test
    void monthlyValuesComeFromObservationInForceAtMonthEndWithinScope() {
        LearningDynamics leader = service.dynamics(LEADER_A_PROFILE, FROM, TO, SeriesBy.ORGANIZATION, null, null);

        assertThat(leader.months()).extracting(LearningDynamics.Month::key, LearningDynamics.Month::asOf).containsExactly(
                tuple("2026-01", LocalDate.parse("2026-01-31")), tuple("2026-02", LocalDate.parse("2026-02-28")),
                tuple("2026-03", LocalDate.parse("2026-03-31")), tuple("2026-04", LocalDate.parse("2026-04-30")),
                tuple("2026-05", LocalDate.parse("2026-05-31")), tuple("2026-06", TO));
        // Н-5 (2.5): январь раньше первого потока — «нет данных», а не ноль.
        assertThat(leader.totalParticipants()).containsExactly(null, 10L, 12L, 12L, 12L, 7L);
        assertThat(leader.totalCompleted()).containsExactly(null, 0L, 2L, 2L, 9L, null);
        assertThat(leader.totalRunsWithoutData()).containsExactly(0L, 0L, 0L, 1L, 1L, 0L);
        // П15: поток «Института без КАМ» идёт с апреля, а первое наблюдение — в июне: апрель и май — «нет данных», не ноль.
        assertThat(leader.series()).extracting(LearningDynamics.Series::label, LearningDynamics.Series::participants)
                .containsExactly(
                        tuple("Институт без КАМ", java.util.Arrays.asList(null, null, null, null, null, 7L)),
                        tuple("Университет «Альфа»", java.util.Arrays.asList(null, 10L, 12L, 12L, 12L, 0L)));
        assertThat(leader.rows()).extracting(LearningDynamics.Row::month, LearningDynamics.Row::organizationName,
                        LearningDynamics.Row::runs, LearningDynamics.Row::runsWithoutData, LearningDynamics.Row::participants,
                        LearningDynamics.Row::completed, LearningDynamics.Row::completionPercent)
                .containsExactly(
                        tuple("2026-02", "Университет «Альфа»", 1L, 0L, 10L, 0L, 0L),
                        tuple("2026-03", "Университет «Альфа»", 1L, 0L, 12L, 2L, 17L),
                        tuple("2026-04", "Институт без КАМ", 0L, 1L, null, null, null),
                        tuple("2026-04", "Университет «Альфа»", 1L, 0L, 12L, 2L, 17L),
                        tuple("2026-05", "Институт без КАМ", 0L, 1L, null, null, null),
                        tuple("2026-05", "Университет «Альфа»", 1L, 0L, 12L, 9L, 75L),
                        tuple("2026-06", "Институт без КАМ", 1L, 0L, 7L, null, null));

        LearningDynamics byProgram = service.dynamics(MANAGER_B_PROFILE, FROM, TO, SeriesBy.PROGRAM, null, null);
        assertThat(byProgram.series()).singleElement().satisfies(series -> {
            assertThat(series.label()).isEqualTo("Анализ данных");
            assertThat(series.participants()).containsExactly(null, null, 8L, 5L, 5L, 5L);
            assertThat(series.completed()).containsOnlyNulls();
        });
        assertThat(service.dynamics(MANAGER_A_PROFILE, FROM, TO, null, List.of(ORGANIZATION_B), null).rows()).isEmpty();
        assertThat(service.dynamics(LEADER_A_PROFILE, FROM, TO, null, null, List.of(PROGRAM_DATA)).rows()).isEmpty();
        // Б-11: бизнес-отчёт администратору недоступен — 403, а не пустой отчёт.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dynamics(ADMIN_PROFILE, FROM, TO, null, null, null))
                .isInstanceOf(ru.rtk.crm.access.AdminCrmProfileAccessDeniedException.class);
        assertThat(leader.notes()).anyMatch(note -> note.contains("действовавшему на конец последнего дня месяца"));
    }

    // П14 и П15: C=16, N=4, U=80 — доля 80 % только среди известных, покрытие 20 из 100; поток без наблюдения не ноль.
    @Test
    void completionShareShowsNumeratorDenominatorAndCoverage() {
        UUID tracked = run(ORGANIZATION_B, PROGRAM, "2026-07-01", "2026-08-01", "STUDENTS");
        observeCounts(tracked, "2026-07-20", 20, 16, 4);
        UUID untracked = run(ORGANIZATION_B, PROGRAM, "2026-07-01", "2026-08-01", "STUDENTS");
        observeCounts(untracked, "2026-07-20", 80, null, null);
        run(ORGANIZATION_B, PROGRAM, "2026-07-01", "2026-08-01", "STUDENTS");
        CrmProfile management = new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0);
        LocalDate july = LocalDate.parse("2026-07-01");

        LearningDynamics dynamics = service.dynamics(management, july, LocalDate.parse("2026-07-31"), null,
                List.of(ORGANIZATION_B), List.of(PROGRAM));

        assertThat(dynamics.rows()).singleElement().satisfies(row -> {
            assertThat(row.runs()).isEqualTo(2);
            assertThat(row.runsWithoutData()).isEqualTo(1);
            assertThat(row.participants()).isEqualTo(100L);
            assertThat(row.completed()).isEqualTo(16L);
            assertThat(row.known()).isEqualTo(20L);
            assertThat(row.completionPercent()).isEqualTo(80L);
            assertThat(row.completionBasis()).isEqualTo("16 из 20 с известным статусом; данные по 20 из 100");
        });
        assertThat(dynamics.totalKnown()).containsExactly(20L);
        assertThat(dynamics.totalRunsWithoutData()).containsExactly(1L);

        UUID silentOrganization = UUID.randomUUID();
        ReportTestData.organization(jdbcTemplate, silentOrganization, "Колледж без наблюдений", ReportTestData.TEAM_B,
                ReportTestData.MANAGER_B);
        run(silentOrganization, PROGRAM, "2026-07-01", "2026-08-01", "STUDENTS");
        LearningDynamics silent = service.dynamics(management, july, LocalDate.parse("2026-07-31"), null,
                List.of(silentOrganization), null);
        assertThat(silent.totalParticipants()).containsExactly((Long) null);
        assertThat(silent.series()).singleElement().satisfies(series -> assertThat(series.participants()).containsExactly((Long) null));
        assertThat(silent.rows()).singleElement().satisfies(row -> {
            assertThat(row.participants()).isNull();
            assertThat(row.completionBasis()).isEqualTo("нет данных: у потоков нет наблюдений");
        });
    }

    @Test
    void leaderTrendCoversOnlyOwnTeamWhileManagementSeesEveryTeamAndOthersAreRefused() {
        LocalDate today = LocalDate.now(ReportRequest.ZONE);
        UUID ownRun = run(ORGANIZATION_A, PROGRAM, today.minusDays(90).toString(), today.plusDays(90).toString(), "STUDENTS");
        observe(ownRun, today.minusDays(40).toString(), 10, null);
        observe(ownRun, today.minusDays(5).toString(), 14, null);
        UUID foreignRun = run(ORGANIZATION_B, PROGRAM_DATA, today.minusDays(90).toString(), today.plusDays(90).toString(), "STUDENTS");
        observe(foreignRun, today.minusDays(40).toString(), 4, null);
        observe(foreignRun, today.minusDays(5).toString(), 6, null);
        CrmProfile management = new CrmProfile(UUID.randomUUID(), UserRole.MANAGEMENT, null, 0);

        LearningTrend leader = trendService.trend(LEADER_A_PROFILE, 30);
        LearningTrend everyone = trendService.trend(management, 30);

        assertThat(leader.teams()).extracting(LearningTrend.Item::name).containsExactly("Команда А");
        assertThat(leader.programs()).extracting(LearningTrend.Item::name).doesNotContain("Анализ данных");
        assertThat(leader.total().end() + leader.total().endWithoutBase()).isEqualTo(14L + 7L);
        assertThat(everyone.teams()).extracting(LearningTrend.Item::name).contains("Команда А", "Команда Б");
        assertThat(everyone.programs()).extracting(LearningTrend.Item::name).contains("Анализ данных");
        assertThat(everyone.total().end()).isGreaterThan(leader.total().end());
        assertThatThrownBy(() -> trendService.trend(MANAGER_A_PROFILE, 30)).isInstanceOf(WorkAccessDeniedException.class);
        assertThatThrownBy(() -> trendService.trend(ADMIN_PROFILE, 30)).isInstanceOf(WorkAccessDeniedException.class);
        assertThatThrownBy(() -> trendService.trend(new CrmProfile(UUID.randomUUID(), UserRole.PARTNER, null, 0), 30))
                .isInstanceOf(WorkAccessDeniedException.class);
    }

    @Test
    void periodIsValidatedAndDefaultsToLastTwelveMonths() {
        LocalDate today = LocalDate.now(ReportRequest.ZONE);
        assertThat(service.dynamics(LEADER_A_PROFILE, null, null, null, null, null).months()).hasSize(12)
                .last().satisfies(month -> assertThat(month.asOf()).isEqualTo(today));
        // Д-1 (2.5): готовый период «Этот год» кончается в будущем — отчёт строится по сегодняшний день, а не отказывает.
        LearningDynamics year = service.dynamics(LEADER_A_PROFILE, today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31),
                null, null, null);
        assertThat(year.to()).isEqualTo(today);
        assertThat(year.months()).last().satisfies(month -> assertThat(month.asOf()).isEqualTo(today));
        assertThatThrownBy(() -> service.dynamics(LEADER_A_PROFILE, TO, FROM, null, null, null))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("to"));
        assertThatThrownBy(() -> service.dynamics(LEADER_A_PROFILE, TO.minusMonths(LearningDynamicsService.MAX_MONTHS), TO,
                null, null, null))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("from"));
    }

    @Test
    void filesContainTheSameRowsAsTheScreen() throws IOException {
        LearningDynamics dynamics = service.dynamics(LEADER_A_PROFILE, FROM, TO, null, null, null);

        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        service.write(dynamics, ReportFormat.XLSX, xlsx);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            int header = dynamics.notes().size() + 2;
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(LearningDynamics.TITLE);
            assertThat(sheet.getRow(header).getCell(5).getStringCellValue()).isEqualTo("Обучающиеся в потоках месяца (Moodle)");
            assertThat(sheet.getRow(header + 1).getCell(5).getNumericCellValue()).isEqualTo(10d);
            assertThat(sheet.getRow(header).getCell(8).getStringCellValue()).isEqualTo("Доля завершивших среди известных, %");
            assertThat(sheet.getRow(header + 2).getCell(8).getNumericCellValue()).isEqualTo(17d);
            assertThat(sheet.getRow(header + 2).getCell(9).getStringCellValue())
                    .isEqualTo("2 из 12 с известным статусом; данные по 12 из 12");
            assertThat(sheet.getRow(header + 3).getCell(4).getNumericCellValue()).isEqualTo(1d);
            assertThat(sheet.getRow(header + 3).getCell(5).getStringCellValue()).isEqualTo(ReportColumn.NO_DATA);
            assertThat(sheet.getRow(header + 7).getCell(6).getStringCellValue()).isEqualTo(ReportColumn.NO_DATA);
            assertThat(sheet.getRow(header + 7).getCell(8).getStringCellValue()).isEqualTo(ReportColumn.NO_DATA);
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        service.write(dynamics, ReportFormat.PDF, pdf);
        assertThat(new String(pdf.toByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(LearningDynamicsService.fileName(dynamics, ReportFormat.PDF))
                .isEqualTo("Динамика_обучения_2026-01-01_2026-06-30.pdf");
        assertThatThrownBy(() -> service.write(dynamics, ReportFormat.JSON, new ByteArrayOutputStream()))
                .isInstanceOf(InteractionValidationException.class);
    }

    private UUID run(UUID organizationId, UUID programId, String startsOn, String endsOn, String kind) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO source_mappings (
                    id, source, kind, external_key, organization_id, program_id, run_starts_on, run_ends_on, run_kind
                ) VALUES (?, 'MOODLE', 'COURSE', ?, ?, ?, ?, ?, ?)
                """, id, id.toString(), organizationId, programId, LocalDate.parse(startsOn), LocalDate.parse(endsOn), kind);
        return id;
    }

    private void observeCounts(UUID run, String observedOn, int participants, Integer completed, Integer notCompleted) {
        OffsetDateTime at = OffsetDateTime.parse(observedOn + "T12:00:00+03:00");
        jdbcTemplate.update("""
                INSERT INTO learning_observations (
                    mapping_id, observed_from, confirmed_at, participants_count, teachers_count, completed_count,
                    not_completed_count, unknown_count, groups_count
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, 0)
                """, run, at, at.plusDays(3), participants, completed, notCompleted,
                participants - (completed == null ? 0 : completed + notCompleted));
    }

    private void observe(UUID run, String observedOn, int participants, Integer completed) {
        OffsetDateTime at = OffsetDateTime.parse(observedOn + "T12:00:00+03:00");
        jdbcTemplate.update("""
                INSERT INTO learning_observations (
                    mapping_id, observed_from, confirmed_at, participants_count, teachers_count, completed_count,
                    not_completed_count, unknown_count, groups_count
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, 0)
                """, run, at, at.plusDays(3), participants, completed, completed == null ? null : participants - completed,
                completed == null ? participants : 0);
    }
}
