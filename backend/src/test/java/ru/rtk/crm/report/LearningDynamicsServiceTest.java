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
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.LearningDynamics.SeriesBy;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:learning-dynamics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        ReportRepository.class,
        LearningHistoryRepository.class,
        LearningDynamicsService.class
})
class LearningDynamicsServiceTest {
    private static final LocalDate FROM = LocalDate.parse("2026-01-01");
    private static final LocalDate TO = LocalDate.parse("2026-06-30");

    @Autowired
    private LearningDynamicsService service;

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
        assertThat(leader.totalParticipants()).containsExactly(0L, 10L, 12L, 12L, 12L, 7L);
        assertThat(leader.totalCompleted()).containsExactly(null, 0L, 2L, 2L, 9L, null);
        assertThat(leader.series()).extracting(LearningDynamics.Series::label, LearningDynamics.Series::participants)
                .containsExactly(
                        tuple("Институт без КАМ", List.of(0L, 0L, 0L, 0L, 0L, 7L)),
                        tuple("Университет «Альфа»", List.of(0L, 10L, 12L, 12L, 12L, 0L)));
        assertThat(leader.rows()).extracting(LearningDynamics.Row::month, LearningDynamics.Row::organizationName,
                        LearningDynamics.Row::runs, LearningDynamics.Row::participants, LearningDynamics.Row::completed)
                .containsExactly(
                        tuple("2026-02", "Университет «Альфа»", 1L, 10L, 0L),
                        tuple("2026-03", "Университет «Альфа»", 1L, 12L, 2L),
                        tuple("2026-04", "Университет «Альфа»", 1L, 12L, 2L),
                        tuple("2026-05", "Университет «Альфа»", 1L, 12L, 9L),
                        tuple("2026-06", "Институт без КАМ", 1L, 7L, null));

        LearningDynamics byProgram = service.dynamics(MANAGER_B_PROFILE, FROM, TO, SeriesBy.PROGRAM, null, null);
        assertThat(byProgram.series()).singleElement().satisfies(series -> {
            assertThat(series.label()).isEqualTo("Анализ данных");
            assertThat(series.participants()).containsExactly(0L, 0L, 8L, 5L, 5L, 5L);
            assertThat(series.completed()).containsOnlyNulls();
        });
        assertThat(service.dynamics(MANAGER_A_PROFILE, FROM, TO, null, List.of(ORGANIZATION_B), null).rows()).isEmpty();
        assertThat(service.dynamics(LEADER_A_PROFILE, FROM, TO, null, null, List.of(PROGRAM_DATA)).rows()).isEmpty();
        assertThat(service.dynamics(ADMIN_PROFILE, FROM, TO, null, null, null)).satisfies(empty -> {
            assertThat(empty.rows()).isEmpty();
            assertThat(empty.totalParticipants()).containsOnly(0L);
        });
        assertThat(leader.notes()).anyMatch(note -> note.contains("действовавшее на конец последнего дня месяца"));
    }

    @Test
    void periodIsValidatedAndDefaultsToLastTwelveMonths() {
        LocalDate today = LocalDate.now(ReportRequest.ZONE);
        assertThat(service.dynamics(LEADER_A_PROFILE, null, null, null, null, null).months()).hasSize(12)
                .last().satisfies(month -> assertThat(month.asOf()).isEqualTo(today));
        assertThatThrownBy(() -> service.dynamics(LEADER_A_PROFILE, null, today.plusDays(1), null, null, null))
                .isInstanceOfSatisfying(InteractionValidationException.class, error -> assertThat(error.field()).isEqualTo("to"));
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
            assertThat(sheet.getRow(header).getCell(4).getStringCellValue()).isEqualTo("Обучающиеся (Moodle)");
            assertThat(sheet.getRow(header + 1).getCell(4).getNumericCellValue()).isEqualTo(10d);
            assertThat(sheet.getRow(header + 5).getCell(5).getStringCellValue()).isEqualTo(ReportColumn.NO_DATA);
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
