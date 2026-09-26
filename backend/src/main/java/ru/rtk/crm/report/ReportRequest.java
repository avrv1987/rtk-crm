package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ru.rtk.crm.interaction.InteractionValidationException;

public record ReportRequest(
        ReportKind kind,
        LocalDate from,
        LocalDate to,
        PeriodBasis periodBasis,
        ReportFilters filters,
        List<ReportColumn> columns,
        ReportFormat format,
        StatisticsGroupBy groupBy,
        ReportColumn sortBy,
        LocalDate asOf,
        ChartType chartType,
        StatisticsGroupBy seriesBy
) {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private static final Set<StatisticsGroupBy> DEMAND_GROUPINGS = EnumSet.of(
            StatisticsGroupBy.ORGANIZATION,
            StatisticsGroupBy.DIRECTION,
            StatisticsGroupBy.PROGRAM,
            StatisticsGroupBy.MANAGER,
            StatisticsGroupBy.MONTH
    );
    private static final Set<ReportColumn> DEMAND_SORTS = EnumSet.of(
            ReportColumn.APPLICATIONS,
            ReportColumn.PARTICIPANTS,
            ReportColumn.LEARNERS_COMPLETED,
            ReportColumn.PARALLEL_RUNS
    );

    public ReportRequest(
            ReportKind kind,
            LocalDate from,
            LocalDate to,
            PeriodBasis periodBasis,
            ReportFilters filters,
            List<ReportColumn> columns,
            ReportFormat format,
            StatisticsGroupBy groupBy,
            ReportColumn sortBy
    ) {
        this(kind, from, to, periodBasis, filters, columns, format, groupBy, sortBy, null, null, null);
    }

    public ReportRequest normalized() {
        if (kind == null) {
            throw new InteractionValidationException(
                    "kind", "Укажите вид отчёта: PORTFOLIO, EVENTS, DEMAND, SNAPSHOT, DURATION или AGREEMENTS"
            );
        }
        ReportFilters normalizedFilters = filters == null ? ReportFilters.none() : filters.normalized();
        if (kind != ReportKind.EVENTS && !normalizedFilters.eventTypes().isEmpty()) {
            throw new InteractionValidationException(
                    "filters.eventTypes", "Вид события выбирается только в отчёте «События за период»"
            );
        }
        if (kind != ReportKind.PORTFOLIO && normalizedFilters.minDaysOnStage() != null) {
            throw new InteractionValidationException(
                    "filters.minDaysOnStage", "Отбор «На этапе дольше N дней» доступен только в отчёте «Портфель взаимодействий»"
            );
        }
        if (kind == ReportKind.DEMAND) {
            requireDemandSelection(normalizedFilters);
        } else if (sortBy != null) {
            throw new InteractionValidationException("sortBy", "Сортировка по показателю доступна только для отчёта DEMAND");
        }
        if (kind == ReportKind.AGREEMENTS) {
            requireAgreementSelection(normalizedFilters);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        LocalDate normalizedAsOf = snapshotDate();
        PeriodBasis basis = kind == ReportKind.PORTFOLIO ? (periodBasis == null ? PeriodBasis.CREATED : periodBasis) : null;
        requireChartSelection(basis);
        return new ReportRequest(
                kind,
                from,
                to,
                basis,
                normalizedFilters,
                groupBy == null ? normalizedColumns() : List.of(),
                format,
                groupBy,
                kind == ReportKind.DEMAND && sortBy == null ? ReportColumn.APPLICATIONS : sortBy,
                normalizedAsOf,
                chartType,
                seriesBy
        );
    }

    public boolean lineChart() {
        return chartType == ChartType.LINE || seriesBy != null;
    }

    private LocalDate snapshotDate() {
        if (kind != ReportKind.SNAPSHOT) {
            if (asOf != null) {
                throw new InteractionValidationException(
                        "asOf", "Дата состояния задаётся только для отчёта «Состояние портфеля на дату»"
                );
            }
            return null;
        }
        if (from != null || to != null) {
            throw new InteractionValidationException(
                    "from", "Для состояния портфеля на дату период не задаётся: укажите одну дату asOf"
            );
        }
        LocalDate today = LocalDate.now(ZONE);
        if (asOf != null && asOf.isAfter(today)) {
            throw new InteractionValidationException("asOf", "Дата состояния не может быть позже сегодняшней");
        }
        return asOf == null ? today : asOf;
    }

    private void requireChartSelection(PeriodBasis basis) {
        if (groupBy == null) {
            if (chartType != null || seriesBy != null) {
                throw new InteractionValidationException("chartType", "Вид диаграммы и линии задаются вместе с группировкой groupBy");
            }
            return;
        }
        if (kind == ReportKind.DURATION) {
            throw new InteractionValidationException(
                    "groupBy", "Для отчёта «Длительность этапов и цикла» диаграмма не строится: показатели уже сгруппированы"
            );
        }
        if (groupBy == StatisticsGroupBy.MONTH && basis != null && basis != PeriodBasis.CREATED) {
            throw new InteractionValidationException(
                    "groupBy",
                    "Группировка по месяцам недоступна для отбора «С событиями в периоде» и «Активные в периоде»: месяц"
                            + " создания взаимодействия может лежать вне периода. Выберите отбор «Созданные в периоде»"
                            + " или отчёт «События за период»"
            );
        }
        if (chartType == ChartType.LINE && groupBy != StatisticsGroupBy.MONTH) {
            throw new InteractionValidationException("chartType", "График (линия) строится только для группировки по месяцам");
        }
        if (seriesBy == null) {
            return;
        }
        if (groupBy != StatisticsGroupBy.MONTH || chartType == ChartType.BAR) {
            throw new InteractionValidationException(
                    "seriesBy", "Отдельные линии по группам строятся только на графике по месяцам"
            );
        }
        if (seriesBy == StatisticsGroupBy.MONTH || kind == ReportKind.DEMAND && !DEMAND_GROUPINGS.contains(seriesBy)) {
            throw new InteractionValidationException(
                    "seriesBy", "Линии строятся по вузам, ИТ-направлениям, ИТ-программам, ответственным"
                            + (kind == ReportKind.DEMAND ? "" : ", этапам или ИТ-продуктам")
            );
        }
    }

    private void requireDemandSelection(ReportFilters normalizedFilters) {
        if (!normalizedFilters.productIds().isEmpty() || normalizedFilters.includeNoProduct()) {
            throw new InteractionValidationException(
                    "filters.productIds", "Фильтр по ИТ-продуктам недоступен для отчёта «Востребованность программ»"
            );
        }
        if (!normalizedFilters.stages().isEmpty()) {
            throw new InteractionValidationException(
                    "filters.stages", "Фильтр по этапам недоступен для отчёта «Востребованность программ»"
            );
        }
        if (!normalizedFilters.workStatuses().isEmpty()) {
            throw new InteractionValidationException(
                    "filters.workStatuses", "Фильтр по состоянию работы недоступен для отчёта «Востребованность программ»"
            );
        }
        if (!normalizedFilters.flags().isEmpty()) {
            throw new InteractionValidationException(
                    "filters.flags", "Фильтр по отметкам работы недоступен для отчёта «Востребованность программ»"
            );
        }
        if (!normalizedFilters.agreement().unrestricted()) {
            throw new InteractionValidationException(
                    "filters.agreement", "Фильтры договора и передачи недоступны для отчёта «Востребованность программ»"
            );
        }
        if (groupBy != null && !DEMAND_GROUPINGS.contains(groupBy)) {
            throw new InteractionValidationException(
                    "groupBy", "Заявки группируются по вузам, ИТ-направлениям, ИТ-программам, ответственным или месяцам"
            );
        }
        if (sortBy != null && !DEMAND_SORTS.contains(sortBy)) {
            throw new InteractionValidationException(
                    "sortBy", "Сортировать можно по заявкам, обучающимся, завершившим или параллельным потокам"
            );
        }
    }

    private void requireAgreementSelection(ReportFilters normalizedFilters) {
        if (!normalizedFilters.directionIds().isEmpty() || normalizedFilters.includeNoDirection()
                || !normalizedFilters.programIds().isEmpty() || normalizedFilters.includeNoProgram()
                || !normalizedFilters.productIds().isEmpty() || normalizedFilters.includeNoProduct()
                || !normalizedFilters.stages().isEmpty()
                || !normalizedFilters.workStatuses().isEmpty() || !normalizedFilters.flags().isEmpty()
                || !normalizedFilters.agreement().unrestricted()) {
            throw new InteractionValidationException(
                    "filters", "Отчёт «Реализация соглашений» отбирается по вузам, типу организации, ответственным и периоду"
            );
        }
        if (groupBy != null) {
            throw new InteractionValidationException("groupBy", "Для отчёта «Реализация соглашений» диаграмма не строится");
        }
    }

    public StatisticsRequest statisticsRequest() {
        return new StatisticsRequest(kind, groupBy, from, to, periodBasis, filters, asOf, seriesBy);
    }

    public OffsetDateTime fromAt() {
        return from == null ? null : from.atStartOfDay(ZONE).toOffsetDateTime();
    }

    public OffsetDateTime toAt() {
        return to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    public OffsetDateTime asOfAt() {
        return asOf == null ? null : asOf.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    public LocalDate runsAsOf() {
        return to == null ? LocalDate.now(ZONE) : to;
    }

    private List<ReportColumn> normalizedColumns() {
        if (columns == null || columns.isEmpty()) {
            return kind.defaultColumns();
        }
        LinkedHashSet<ReportColumn> selected = new LinkedHashSet<>();
        for (ReportColumn column : columns) {
            if (column == null || !kind.columns().contains(column)) {
                throw new InteractionValidationException("columns", "Колонка недоступна для отчёта " + kind);
            }
            selected.add(column);
        }
        return List.copyOf(selected);
    }
}
