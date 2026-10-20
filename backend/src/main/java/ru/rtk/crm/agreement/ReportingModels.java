package ru.rtk.crm.agreement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.rtk.crm.agreement.AgreementModels.ActivityAttribute;

// Отчётность по соглашениям: справочники, ставки, настройки, перечни мероприятий и отчёт.
public final class ReportingModels {
    private ReportingModels() {
    }

    public enum ReferenceCategory {
        EXECUTOR("Исполнитель"),
        EDUCATION_LEVEL("Уровень образования"),
        UNIT("Единица");

        private final String title;

        ReferenceCategory(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public record ReferenceItem(
            UUID id,
            ReferenceCategory category,
            String name,
            BigDecimal baseAmount,
            int sortOrder,
            boolean archived,
            int version
    ) {
    }

    public record ReferenceCreateRequest(
            @NotNull ReferenceCategory category,
            @NotBlank @Size(max = 200) String name,
            BigDecimal baseAmount
    ) {
    }

    public record ReferenceUpdateRequest(
            @NotNull @Min(0) Integer version,
            @NotBlank @Size(max = 200) String name,
            @NotNull Boolean archived,
            BigDecimal baseAmount
    ) {
    }

    public record Rate(
            UUID id,
            UUID kindId,
            String kindName,
            UUID unitId,
            String unitName,
            UUID educationLevelId,
            String educationLevelName,
            LocalDate effectiveFrom,
            BigDecimal rate,
            int version
    ) {
    }

    public record RateRequest(
            @Min(0) Integer version,
            @NotNull UUID kindId,
            @NotNull UUID unitId,
            UUID educationLevelId,
            @NotNull LocalDate effectiveFrom,
            @NotNull BigDecimal rate
    ) {
    }

    public enum PeriodKind {
        CALENDAR,
        ACADEMIC
    }

    public record Settings(
            PeriodKind periodKind,
            int academicYearStartMonth,
            Integer silenceDays,
            Integer repeatSilenceDays,
            int reminderDays,
            boolean thresholdEnabled,
            BigDecimal thresholdPercent,
            BigDecimal thresholdBase,
            String thresholdBaseLabel
    ) {
    }

    public record SettingsRequest(
            @NotNull PeriodKind periodKind,
            @NotNull @Min(1) @Max(12) Integer academicYearStartMonth,
            @Min(1) @Max(365) Integer silenceDays,
            @Min(1) @Max(365) Integer repeatSilenceDays,
            @NotNull @Min(1) @Max(365) Integer reminderDays,
            @NotNull Boolean thresholdEnabled,
            BigDecimal thresholdPercent,
            BigDecimal thresholdBase,
            @Size(max = 300) String thresholdBaseLabel
    ) {
    }

    public record ExportColumn(@NotBlank @Size(max = 40) String code, @Size(max = 200) String title) {
    }

    public record ColumnOption(String code, String defaultTitle) {
    }

    public record TemplateRequest(@NotNull @Size(max = 40) List<@Valid ExportColumn> columns) {
    }

    public record Deadline(UUID id, String name, LocalDate dueOn, String deliverable, boolean annual) {
    }

    public record DeadlineInput(
            @NotBlank @Size(max = 200) String name,
            @NotNull LocalDate dueOn,
            @Size(max = 500) String deliverable,
            Boolean annual
    ) {
    }

    public record DeadlinesRequest(@NotNull @Size(max = 50) List<@Valid DeadlineInput> deadlines) {
    }

    public record AttributeOption(ActivityAttribute code, String title) {
    }

    public record SettingsView(
            Settings settings,
            List<ExportColumn> columns,
            boolean defaultColumns,
            List<ColumnOption> availableColumns,
            List<Deadline> deadlines,
            List<AttributeOption> attributes,
            PeriodRange currentPeriod,
            OffsetDateTime updatedAt,
            String updatedByName
    ) {
    }

    public record PeriodRange(int year, LocalDate from, LocalDate to, String label) {
    }

    public enum ListStatus {
        DRAFT("Проект"),
        REVIEW("На согласовании"),
        APPROVED("Согласован"),
        APPROVED_SILENTLY("Согласован без ответа"),
        RETURNED("Возвращён");

        private final String title;

        ListStatus(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public boolean editable() {
            return this == DRAFT || this == RETURNED;
        }
    }

    public enum ListChannel {
        KAM,
        COORDINATOR,
        SYSTEM
    }

    public enum ListAction {
        SEND,
        APPROVE,
        RETURN
    }

    public record ActivityListSummary(
            UUID id,
            UUID organizationId,
            String organizationName,
            String title,
            LocalDate periodFrom,
            LocalDate periodTo,
            String periodLabel,
            ListStatus status,
            int reviewRound,
            LocalDate sentOn,
            LocalDate reviewDueOn,
            LocalDate decidedOn,
            int itemCount,
            int version
    ) {
    }

    public record ActivityListItem(
            UUID activityId,
            String agreementNumber,
            String kindName,
            String title,
            String educationLevel,
            String executor,
            String unit,
            Integer plannedVolume,
            Integer participants,
            BigDecimal cost,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
    }

    public record ActivityListEvent(
            UUID id,
            ListStatus fromStatus,
            ListStatus toStatus,
            ListChannel channel,
            String comment,
            String actorName,
            OffsetDateTime occurredAt
    ) {
    }

    public record ActivityList(
            ActivityListSummary summary,
            List<ActivityListItem> items,
            List<ActivityListEvent> history,
            boolean coordinatorAvailable,
            List<ListAction> actions
    ) {
    }

    public record ActivityListsView(boolean coordinatorAvailable, PeriodRange currentPeriod, List<ActivityListSummary> lists) {
    }

    public record ActivityListCreateRequest(
            @Size(max = 200) String title,
            @NotNull @Min(2000) @Max(2100) Integer year,
            @Size(max = 500) List<UUID> activityIds
    ) {
    }

    public record ActivityListUpdateRequest(
            @NotNull @Min(0) Integer version,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 500) List<UUID> activityIds
    ) {
    }

    public record ActivityListTransitionRequest(
            @NotNull @Min(0) Integer version,
            @NotNull ListAction action,
            @Size(max = 2000) String comment,
            LocalDate respondedOn
    ) {
    }

    public record PartnerDecisionRequest(
            @NotNull @Min(0) Integer version,
            @NotNull ListAction decision,
            @Size(max = 2000) String comment
    ) {
    }

    public record DeadlineReminder(UUID id, String name, LocalDate dueOn, String deliverable, long daysLeft) {
    }

    public record ListReminder(
            UUID id,
            UUID organizationId,
            String organizationName,
            String title,
            ListStatus status,
            LocalDate reviewDueOn
    ) {
    }

    public record ReportingReminders(int reminderDays, List<DeadlineReminder> deadlines, List<ListReminder> lists) {
        public static ReportingReminders empty() {
            return new ReportingReminders(0, List.of(), List.of());
        }
    }

    public record Quantity(String unit, long planned, long actual) {
    }

    public record ReportGroup(
            UUID id,
            String name,
            int activities,
            long participants,
            List<Quantity> quantities,
            BigDecimal cost,
            BigDecimal plannedValuation,
            BigDecimal actualValuation
    ) {
    }

    public record ReportTotals(
            int organizations,
            int activities,
            long participants,
            BigDecimal cost,
            BigDecimal plannedValuation,
            BigDecimal actualValuation,
            int withoutRate
    ) {
    }

    public record ThresholdRow(
            UUID executorId,
            String name,
            BigDecimal plannedValuation,
            BigDecimal actualValuation,
            BigDecimal base,
            BigDecimal required,
            Boolean plannedMet,
            Boolean actualMet
    ) {
    }

    public record ThresholdSummary(
            boolean enabled,
            BigDecimal percent,
            String baseLabel,
            List<ThresholdRow> rows,
            ThresholdRow total
    ) {
    }

    public record AgreementReport(
            OffsetDateTime generatedAt,
            LocalDate from,
            LocalDate to,
            List<String> notes,
            List<ExportColumn> columns,
            List<List<Object>> rows,
            ReportTotals totals,
            List<ReportGroup> byOrganization,
            List<ReportGroup> byKind,
            List<ReportGroup> byExecutor,
            List<ReportGroup> byLevel,
            ThresholdSummary threshold
    ) {
    }
}
