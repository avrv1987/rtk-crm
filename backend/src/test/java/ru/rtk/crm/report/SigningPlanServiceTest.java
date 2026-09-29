package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.rtk.crm.report.ReportTestData.ADMIN_PROFILE;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_B_PROFILE;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_A_UNASSIGNED;
import static ru.rtk.crm.report.ReportTestData.ORGANIZATION_B;
import static ru.rtk.crm.report.ReportTestData.TEAM_A;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
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
import ru.rtk.crm.agreement.AgreementModels.PlanKind;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.SigningPlan.Source;
import ru.rtk.crm.report.SigningPlan.State;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:signing-plan;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrganizationRepository.class, SigningPlanRepository.class, SigningPlanService.class})
class SigningPlanServiceTest {
    private static final CrmProfile MANAGEMENT = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-0000000000f1"),
            UserRole.MANAGEMENT, null, 0);
    private static final CrmProfile PARTNER = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-0000000000f2"),
            UserRole.PARTNER, null, 0);

    @Autowired
    private SigningPlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbc);
        ReportTestData.insertScenario(jdbc);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    number VARCHAR(100) NOT NULL,
                    concluded_on DATE,
                    valid_until DATE,
                    planned_kind VARCHAR(16),
                    planned_on DATE,
                    planned_base_until DATE,
                    status VARCHAR(16) NOT NULL
                )
                """);
        jdbc.update("DELETE FROM agreements");
        agreement(ORGANIZATION_A, "П-1", "DRAFT", null, "SIGNING", "2026-02-10", null);
        agreement(ORGANIZATION_A, "П-2", "ACTIVE", "2027-03-01", "SIGNING", "2026-03-01", null);
        agreement(ORGANIZATION_B, "П-3", "ACTIVE", "2027-03-10", "RENEWAL", "2026-03-15", "2026-03-10");
        agreement(ORGANIZATION_B, "П-4", "ACTIVE", "2026-03-25", "RENEWAL", "2026-03-20", "2026-03-25");
        agreement(ORGANIZATION_A_UNASSIGNED, "П-5", "ACTIVE", "2026-02-20", null, null, null);
        agreement(ORGANIZATION_A, "П-6", "TERMINATED", null, "SIGNING", "2026-01-15", null);
        agreement(ORGANIZATION_A, "П-7", "ACTIVE", "2027-01-01", null, null, null);
        agreement(ORGANIZATION_A, "П-8", "DRAFT", null, "SIGNING", "2026-04-05", null);
        agreement(ORGANIZATION_A, "П-9", "ACTIVE", "2098-12-31", "RENEWAL", "2099-02-01", "2098-12-31");
        UUID archived = UUID.fromString("00000000-0000-0000-0000-000000000199");
        jdbc.update("INSERT INTO organizations (id, name, type, team_id, owner_manager_id, status) VALUES (?, ?, 'UNIVERSITY', ?, NULL, 'ARCHIVED')",
                archived, "Архивный вуз", TEAM_A);
        agreement(archived, "П-10", "DRAFT", null, "SIGNING", "2026-02-11", null);
    }

    @Test
    void leaderSeesTeamRowsWithKindSourceAndStateAndTotalsPerManagerAndTeam() {
        SigningPlan plan = service.plan(LEADER_A_PROFILE, 2026, 1);

        assertThat(plan.quarter()).isEqualTo(1);
        assertThat(plan.from()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(plan.to()).isEqualTo(LocalDate.parse("2026-03-31"));
        assertThat(plan.rows()).extracting(SigningPlan.Row::agreementNumber, SigningPlan.Row::kind, SigningPlan.Row::source,
                        SigningPlan.Row::state)
                .containsExactly(
                        tuple("П-1", PlanKind.SIGNING, Source.PLAN, State.OVERDUE),
                        tuple("П-5", PlanKind.RENEWAL, Source.EXPIRY, State.OVERDUE),
                        tuple("П-2", PlanKind.SIGNING, Source.PLAN, State.DONE));
        assertThat(plan.rows().get(1).plannedOn()).isEqualTo(LocalDate.parse("2026-02-20"));
        assertThat(plan.rows().get(1).managerId()).isNull();
        assertThat(plan.byTeam()).singleElement().satisfies(team -> {
            assertThat(team.name()).isEqualTo("Команда А");
            assertThat(team.signing()).isEqualTo(2);
            assertThat(team.renewal()).isEqualTo(1);
            assertThat(team.done()).isEqualTo(1);
            assertThat(team.overdue()).isEqualTo(2);
            assertThat(team.upcoming()).isZero();
        });
        assertThat(plan.byManager()).extracting(SigningPlan.Totals::name).containsExactly("Анна Кузнецова", "Без ответственного КАМ");
    }

    @Test
    void renewalIsDoneOnlyWhenTheExpiryMovedLaterThanAtPlanningTime() {
        SigningPlan plan = service.plan(MANAGER_B_PROFILE, 2026, 1);

        assertThat(plan.rows()).extracting(SigningPlan.Row::agreementNumber, SigningPlan.Row::state)
                .containsExactly(tuple("П-3", State.DONE), tuple("П-4", State.OVERDUE));
    }

    @Test
    void managerSeesOnlyOwnUniversitiesAndManagementSeesAllTeams() {
        assertThat(service.plan(MANAGER_A_PROFILE, 2026, 1).rows()).extracting(SigningPlan.Row::agreementNumber)
                .containsExactly("П-1", "П-2");
        SigningPlan management = service.plan(MANAGEMENT, 2026, 1);

        assertThat(management.rows()).extracting(SigningPlan.Row::agreementNumber)
                .containsExactly("П-1", "П-5", "П-2", "П-3", "П-4");
        assertThat(management.byTeam()).extracting(SigningPlan.Totals::name).containsExactly("Команда А", "Команда Б");
    }

    @Test
    void administratorAndPartnerGetNoBusinessRowsAndTerminatedAndArchivedAreSkipped() {
        assertThat(service.plan(ADMIN_PROFILE, 2026, 1).rows()).isEmpty();
        assertThat(service.plan(PARTNER, 2026, 1).rows()).isEmpty();
        assertThat(service.plan(MANAGEMENT, 2026, 1).rows()).extracting(SigningPlan.Row::agreementNumber)
                .doesNotContain("П-6", "П-10");
    }

    @Test
    void yearWithoutQuarterCoversTheWholeYearAndFutureDatesAreAhead() {
        SigningPlan year = service.plan(LEADER_A_PROFILE, 2026, null);

        assertThat(year.quarter()).isNull();
        assertThat(year.to()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(year.rows()).extracting(SigningPlan.Row::agreementNumber).contains("П-8");
        assertThat(service.plan(LEADER_A_PROFILE, 2099, 1).rows()).singleElement().satisfies(row -> {
            assertThat(row.agreementNumber()).isEqualTo("П-9");
            assertThat(row.state()).isEqualTo(State.UPCOMING);
        });
    }

    @Test
    void periodDefaultsToCurrentQuarterAndRejectsBadYearAndQuarter() {
        LocalDate today = LocalDate.now(ReportRequest.ZONE);
        SigningPlan current = service.plan(LEADER_A_PROFILE, null, null);

        assertThat(current.quarter()).isEqualTo((today.getMonthValue() - 1) / 3 + 1);
        assertThat(current.year()).isEqualTo(today.getYear());
        assertThatThrownBy(() -> service.plan(LEADER_A_PROFILE, 2026, 5))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("quarter"));
        assertThatThrownBy(() -> service.plan(LEADER_A_PROFILE, 1999, 1))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo("year"));
    }

    @Test
    void xlsxAndPdfFilesCarryRowsAndTotals() throws IOException {
        SigningPlan plan = service.plan(MANAGEMENT, 2026, 1);
        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        service.write(plan, ReportFormat.XLSX, xlsx);
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        service.write(plan, ReportFormat.PDF, pdf);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx.toByteArray()))) {
            Sheet sheet = workbook.getSheet("План");
            List<String> cells = new ArrayList<>();
            sheet.forEach(row -> row.forEach(cell -> cells.add(cell.toString())));
            assertThat(cells).contains("Университет «Альфа»", "П-1", "Подписание", "просрочено", "выполнено", "Строк в отчёте: 5");
            assertThat(workbook.getSheet("Итоги")).isNotNull();
        }
        assertThat(new String(pdf.toByteArray(), 0, 5)).isEqualTo("%PDF-");
        assertThat(SigningPlanService.fileName(plan, ReportFormat.XLSX)).isEqualTo("План_подписаний_2026-01-01_2026-03-31.xlsx");
        assertThatThrownBy(() -> service.write(plan, ReportFormat.JSON, new ByteArrayOutputStream()))
                .isInstanceOf(InteractionValidationException.class);
    }

    private void agreement(UUID organizationId, String number, String status, String validUntil, String kind, String plannedOn,
            String base) {
        jdbc.update("""
                INSERT INTO agreements (id, organization_id, number, valid_until, planned_kind, planned_on, planned_base_until, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organizationId, number, date(validUntil), kind, date(plannedOn), date(base), status);
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
