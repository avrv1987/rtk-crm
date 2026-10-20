package ru.rtk.crm.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.agreement.AgreementModels.ActivityStatus;
import ru.rtk.crm.agreement.ReportingModels.AgreementReport;
import ru.rtk.crm.agreement.ReportingModels.Deadline;
import ru.rtk.crm.agreement.ReportingModels.ExportColumn;
import ru.rtk.crm.agreement.ReportingModels.PeriodKind;
import ru.rtk.crm.agreement.ReportingModels.Rate;
import ru.rtk.crm.agreement.ReportingModels.ReferenceCategory;
import ru.rtk.crm.agreement.ReportingModels.ReferenceItem;
import ru.rtk.crm.agreement.ReportingModels.Settings;
import ru.rtk.crm.agreement.ReportingModels.ThresholdRow;
import ru.rtk.crm.agreement.ReportingRepository.ReportActivity;

// Расчёт без базы: ставка с датой действия, оценка, свод против порога, период и ближайший ежегодный срок.
class AgreementReportCalculationTest {
    private static final UUID KIND = UUID.randomUUID();
    private static final UUID OTHER_KIND = UUID.randomUUID();
    private static final UUID HOURS = UUID.randomUUID();
    private static final UUID PEOPLE = UUID.randomUUID();
    private static final UUID HIGHER = UUID.randomUUID();
    private static final UUID SECONDARY = UUID.randomUUID();
    private static final UUID EXECUTOR = UUID.randomUUID();

    private static final List<Rate> RATES = List.of(
            rate(KIND, HOURS, null, "2025-01-01", "3.50"),
            rate(KIND, HOURS, null, "2026-01-01", "4.00"),
            rate(KIND, HOURS, HIGHER, "2026-04-25", "4.14"),
            rate(KIND, HOURS, SECONDARY, "2026-04-25", "3.90"),
            rate(KIND, PEOPLE, null, "2026-01-01", "100.00"),
            rate(OTHER_KIND, HOURS, null, "2020-01-01", "1.00")
    );

    @Test
    void rateInForceOnTheActivityDatePrefersTheSameEducationLevel() {
        assertThat(AgreementReportService.rateFor(RATES, KIND, HOURS, HIGHER, LocalDate.parse("2026-04-24"))).isEqualByComparingTo("4.00");
        assertThat(AgreementReportService.rateFor(RATES, KIND, HOURS, HIGHER, LocalDate.parse("2026-04-25"))).isEqualByComparingTo("4.14");
        assertThat(AgreementReportService.rateFor(RATES, KIND, HOURS, SECONDARY, LocalDate.parse("2026-12-31"))).isEqualByComparingTo("3.90");
        assertThat(AgreementReportService.rateFor(RATES, KIND, HOURS, null, LocalDate.parse("2026-12-31"))).isEqualByComparingTo("4.00");
        assertThat(AgreementReportService.rateFor(RATES, KIND, HOURS, null, LocalDate.parse("2025-06-30"))).isEqualByComparingTo("3.50");
        assertThat(AgreementReportService.rateFor(RATES, KIND, HOURS, null, LocalDate.parse("2024-12-31"))).isNull();
        assertThat(AgreementReportService.rateFor(RATES, KIND, null, null, LocalDate.parse("2026-12-31"))).isNull();
        assertThat(AgreementReportService.rateFor(RATES, KIND, HOURS, null, null)).isNull();
        assertThat(AgreementReportService.rateFor(List.of(), KIND, HOURS, null, LocalDate.parse("2026-12-31"))).isNull();
    }

    @Test
    void valuationMultipliesPlanAndFactByTheRateAndThresholdIsBaseTimesPercent() {
        List<ReportActivity> activities = List.of(
                activity("Часы", HOURS, HIGHER, EXECUTOR, 100, 90, "2026-05-10"),
                activity("Люди", PEOPLE, null, EXECUTOR, 3, null, "2026-02-01"),
                activity("Без исполнителя", HOURS, null, null, 10, 10, "2026-02-01")
        );
        Settings settings = new Settings(PeriodKind.CALENDAR, 9, null, null, 14, true, new BigDecimal("3"), null, "база");
        List<ReferenceItem> executors = List.of(new ReferenceItem(EXECUTOR, ReferenceCategory.EXECUTOR, "ДЗО", new BigDecimal("20000"), 10,
                false, 0));
        AgreementReport report = AgreementReportService.build(OffsetDateTime.now(), LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-12-31"), settings,
                List.of(new ExportColumn("TITLE", "Мероприятие"), new ExportColumn("PLANNED_VALUATION", "План"),
                        new ExportColumn("ACTUAL_VALUATION", "Факт")),
                activities, Map.of(), RATES, executors);

        assertThat(report.rows().get(0)).containsExactly("Часы", new BigDecimal("414.00"), new BigDecimal("372.60"));
        assertThat(report.rows().get(1)).containsExactly("Люди", new BigDecimal("300.00"), null);
        assertThat(report.totals().plannedValuation()).isEqualByComparingTo("754.00");
        assertThat(report.totals().actualValuation()).isEqualByComparingTo("412.60");

        ThresholdRow executor = report.threshold().rows().get(0);
        assertThat(executor.required()).isEqualByComparingTo("600.00");
        assertThat(executor.plannedMet()).isTrue();
        assertThat(executor.actualMet()).isFalse();
        ThresholdRow unassigned = report.threshold().rows().get(1);
        assertThat(unassigned.name()).isEqualTo(AgreementReportService.NOT_SET);
        assertThat(unassigned.required()).isNull();
        assertThat(report.threshold().total().base()).isEqualByComparingTo("20000");
        assertThat(report.threshold().total().actualMet()).isFalse();

        Settings off = new Settings(PeriodKind.CALENDAR, 9, null, null, 14, false, null, null, null);
        AgreementReport plain = AgreementReportService.build(OffsetDateTime.now(), LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-12-31"), off, List.of(new ExportColumn("TITLE", "Мероприятие")), activities, Map.of(), RATES, executors);
        assertThat(plain.threshold().enabled()).isFalse();
        assertThat(plain.threshold().rows()).isEmpty();
        assertThat(AgreementReportService.text(new BigDecimal("1234567.5"))).isEqualTo("1 234 567,50");
    }

    @Test
    void periodFollowsTheSettingAndAnnualDeadlineRollsToTheNextYear() {
        ReportingSettingsService service = new ReportingSettingsService(null, null, null, null, null, null, null);
        Settings academic = new Settings(PeriodKind.ACADEMIC, 9, null, null, 14, false, null, null, null);
        Settings calendar = new Settings(PeriodKind.CALENDAR, 9, null, null, 14, false, null, null, null);
        assertThat(service.currentPeriod(academic, LocalDate.parse("2026-08-31")).label()).isEqualTo("2025/2026 учебный год");
        assertThat(service.currentPeriod(academic, LocalDate.parse("2026-09-01")).to()).isEqualTo(LocalDate.parse("2027-08-31"));
        assertThat(service.currentPeriod(calendar, LocalDate.parse("2026-08-31")).label()).isEqualTo("2026 год");

        Deadline annual = new Deadline(UUID.randomUUID(), "Перечень", LocalDate.parse("2020-11-10"), null, true);
        Deadline once = new Deadline(UUID.randomUUID(), "Разовый", LocalDate.parse("2026-11-01"), null, false);
        assertThat(ReportingSettingsService.nextOccurrence(annual, LocalDate.parse("2026-11-10"))).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(ReportingSettingsService.nextOccurrence(annual, LocalDate.parse("2026-11-11"))).isEqualTo(LocalDate.parse("2027-11-10"));
        assertThat(ReportingSettingsService.upcomingDeadlines(List.of(annual, once), LocalDate.parse("2026-10-31"), 10))
                .extracting(ReportingModels.DeadlineReminder::name).containsExactly("Разовый", "Перечень");
        assertThat(ReportingSettingsService.upcomingDeadlines(List.of(once), LocalDate.parse("2026-11-02"), 10)).isEmpty();
    }

    private static Rate rate(UUID kind, UUID unit, UUID level, String from, String value) {
        return new Rate(UUID.randomUUID(), kind, "вид", unit, "ед.", level, null, LocalDate.parse(from), new BigDecimal(value), 0);
    }

    private static ReportActivity activity(String title, UUID unit, UUID level, UUID executor, Integer planned, Integer actual, String date) {
        LocalDate day = LocalDate.parse(date);
        return new ReportActivity(UUID.randomUUID(), "Вуз", "1", null, KIND, "вид", UUID.randomUUID(), title, ActivityStatus.DONE, level,
                level == null ? null : "ВО", executor, executor == null ? null : "ДЗО", unit, "ед.", planned, actual, null, null, day, day,
                day, null, null);
    }
}
