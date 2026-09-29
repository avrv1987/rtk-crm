package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.rtk.crm.report.ReportTestData.LEADER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_A_PROFILE;
import static ru.rtk.crm.report.ReportTestData.MANAGER_B_PROFILE;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:saved-reports;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        CommandIdempotencyRepository.class,
        SavedReportRepository.class,
        SavedReportService.class,
        SavedReportServiceTest.JsonConfiguration.class
})
class SavedReportServiceTest {
    private static final List<ReportColumn> ORDERED = List.of(
            ReportColumn.MANAGER, ReportColumn.ORGANIZATION, ReportColumn.STAGE, ReportColumn.DIRECTION, ReportColumn.PRODUCTS
    );

    @Autowired
    private SavedReportService savedReportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ReportTestData.createSchema(jdbcTemplate);
        ReportTestData.insertScenario(jdbcTemplate);
    }

    @Test
    void ownerSavesReopensAndReordersColumnsWhileOthersNeverSeeTheReport() {
        SavedReport created = savedReportService.create(MANAGER_A_PROFILE, request(" Мой ежемесячный отчёт ", ORDERED, null), "save-1");

        assertThat(created.name()).isEqualTo("Мой ежемесячный отчёт");
        assertThat(created.version()).isZero();
        assertThat(created.definition().columns()).containsExactlyElementsOf(ORDERED);
        assertThat(created.definition().format()).isNull();
        assertThat(savedReportService.list(MANAGER_A_PROFILE)).extracting(SavedReport::id).containsExactly(created.id());
        assertThat(savedReportService.list(MANAGER_B_PROFILE)).isEmpty();
        assertThat(savedReportService.list(LEADER_A_PROFILE)).isEmpty();

        List<ReportColumn> reordered = List.of(ReportColumn.ORGANIZATION, ReportColumn.MANAGER);
        SavedReport updated = savedReportService.update(
                MANAGER_A_PROFILE, created.id(), request("Мой ежемесячный отчёт", reordered, 0), "save-2");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(savedReportService.list(MANAGER_A_PROFILE).getFirst().definition().columns()).containsExactlyElementsOf(reordered);

        assertThatThrownBy(() -> savedReportService.update(MANAGER_B_PROFILE, created.id(), request("Чужой", reordered, 1), "save-3"))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> savedReportService.delete(MANAGER_B_PROFILE, created.id(), 1, "save-4"))
                .isInstanceOfSatisfying(ReportException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));

        savedReportService.delete(MANAGER_A_PROFILE, created.id(), 1, "save-5");
        savedReportService.delete(MANAGER_A_PROFILE, created.id(), 1, "save-5");
        assertThat(savedReportService.list(MANAGER_A_PROFILE)).isEmpty();
    }

    @Test
    void managementKeepsItsOwnSavedReportsWithoutSeeingOthers() {
        CrmProfile management = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-0000000000f1"), UserRole.MANAGEMENT, null, 0);
        SavedReport own = savedReportService.create(management, request("Сводка руководства", ORDERED, null), "mgmt-1");
        SavedReport renamed = savedReportService.update(management, own.id(), request("Сводка за квартал", ORDERED, 0), "mgmt-2");

        assertThat(savedReportService.list(management)).extracting(SavedReport::name).containsExactly("Сводка за квартал");
        assertThat(renamed.version()).isEqualTo(1);
        assertThat(savedReportService.list(MANAGER_A_PROFILE)).isEmpty();
        savedReportService.delete(management, own.id(), 1, "mgmt-3");
        assertThat(savedReportService.list(management)).isEmpty();
    }

    @Test
    void repeatedKeyReplaysAndDifferentPayloadUnderSameKeyConflicts() {
        SavedReport first = savedReportService.create(MANAGER_A_PROFILE, request("Отчёт к планёрке", ORDERED, null), "same-key");

        assertThat(savedReportService.create(MANAGER_A_PROFILE, request("Отчёт к планёрке", ORDERED, null), "same-key").id())
                .isEqualTo(first.id());
        assertThatThrownBy(() -> savedReportService.create(MANAGER_A_PROFILE, request("Другой", ORDERED, null), "same-key"))
                .isInstanceOf(InteractionConflictException.class);
        assertThat(savedReportService.list(MANAGER_A_PROFILE)).hasSize(1);
    }

    @Test
    void staleVersionDuplicateNameAndInvalidDefinitionAreRejected() {
        SavedReport report = savedReportService.create(MANAGER_A_PROFILE, request("Отчёт", ORDERED, null), "v-1");
        savedReportService.update(MANAGER_A_PROFILE, report.id(), request("Отчёт", ORDERED.subList(0, 2), 0), "v-2");

        assertThatThrownBy(() -> savedReportService.update(MANAGER_A_PROFILE, report.id(), request("Отчёт", ORDERED, 0), "v-3"))
                .isInstanceOfSatisfying(ReportException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                });
        assertThatThrownBy(() -> savedReportService.delete(MANAGER_A_PROFILE, report.id(), 0, "v-4"))
                .isInstanceOfSatisfying(ReportException.class, exception -> assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));
        assertThatThrownBy(() -> savedReportService.create(MANAGER_A_PROFILE, request("ОТЧЁТ", ORDERED, null), "v-5"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("name"));
        SavedReportRequest invalid = new SavedReportRequest("Состояние", new ReportRequest(ReportKind.SNAPSHOT,
                LocalDate.parse("2026-09-01"), null, null, null, null, null, null, null, null, null, null), null, null);
        assertThatThrownBy(() -> savedReportService.create(MANAGER_A_PROFILE, invalid, "v-6"))
                .isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> savedReportService.create(MANAGER_A_PROFILE, request("Без ключа", ORDERED, null), " "))
                .isInstanceOf(InteractionValidationException.class);
        assertThat(savedReportService.list(MANAGER_A_PROFILE)).extracting(SavedReport::version).containsExactly(1);
    }

    @Test
    void relativePeriodOpensWithDatesOfTheCurrentDayAndIsNotStoredAsFixedDates() {
        SavedReport monthly = savedReportService.create(
                MANAGER_A_PROFILE, relative("Мой ежемесячный отчёт", SavedReportPeriod.PREVIOUS_MONTH), "period-1");
        LocalDate previousMonth = LocalDate.now(ReportRequest.ZONE).withDayOfMonth(1).minusMonths(1);

        assertThat(monthly.period()).isEqualTo(SavedReportPeriod.PREVIOUS_MONTH);
        assertThat(savedReportService.list(MANAGER_A_PROFILE).getFirst().definition())
                .extracting(ReportRequest::from, ReportRequest::to)
                .containsExactly(previousMonth, previousMonth.plusMonths(1).minusDays(1));
        assertThat(jdbcTemplate.queryForObject("SELECT definition_json FROM saved_reports WHERE id = ?", String.class, monthly.id()))
                .doesNotContain("2026-09-01", "2026-09-30");
        assertThatThrownBy(() -> savedReportService.create(
                MANAGER_A_PROFILE, relative("Мой ежемесячный отчёт", SavedReportPeriod.CURRENT_MONTH), "period-1"))
                .isInstanceOf(InteractionConflictException.class);

        SavedReport fixed = savedReportService.update(
                MANAGER_A_PROFILE, monthly.id(), request("Мой ежемесячный отчёт", ORDERED, 0), "period-2");
        assertThat(fixed.period()).isNull();
        assertThat(fixed.definition().from()).isEqualTo(LocalDate.parse("2026-09-01"));

        SavedReportRequest snapshot = new SavedReportRequest("Состояние", new ReportRequest(ReportKind.SNAPSHOT, null, null,
                null, null, null, null, null, null, null, null, null), null, SavedReportPeriod.CURRENT_MONTH);
        assertThatThrownBy(() -> savedReportService.create(MANAGER_A_PROFILE, snapshot, "period-3"))
                .isInstanceOfSatisfying(InteractionValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("period"));
    }

    @Test
    void periodPresetsAreCalendarMonthsAndQuarters() {
        LocalDate january = LocalDate.parse("2027-01-10");
        LocalDate august = LocalDate.parse("2026-08-31");

        assertThat(List.of(SavedReportPeriod.PREVIOUS_MONTH.from(january), SavedReportPeriod.PREVIOUS_MONTH.to(january)))
                .containsExactly(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"));
        assertThat(List.of(SavedReportPeriod.CURRENT_MONTH.from(august), SavedReportPeriod.CURRENT_MONTH.to(august)))
                .containsExactly(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        assertThat(List.of(SavedReportPeriod.CURRENT_QUARTER.from(august), SavedReportPeriod.CURRENT_QUARTER.to(august)))
                .containsExactly(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-30"));
        assertThat(List.of(SavedReportPeriod.PREVIOUS_QUARTER.from(january), SavedReportPeriod.PREVIOUS_QUARTER.to(january)))
                .containsExactly(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"));
    }

    private static SavedReportRequest relative(String name, SavedReportPeriod period) {
        SavedReportRequest fixed = request(name, ORDERED, null);
        return new SavedReportRequest(fixed.name(), fixed.definition(), null, period);
    }

    private static SavedReportRequest request(String name, List<ReportColumn> columns, Integer version) {
        return new SavedReportRequest(name, new ReportRequest(ReportKind.PORTFOLIO, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-30"), PeriodBasis.CREATED, ReportFilters.none(), columns, ReportFormat.XLSX, null, null,
                null, null, null), version, null);
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder()
                    .findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
        }
    }
}
