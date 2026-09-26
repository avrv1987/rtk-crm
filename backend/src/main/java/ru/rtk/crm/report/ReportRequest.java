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
        ReportColumn sortBy
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
            ReportColumn.PARALLEL_RUNS
    );

    public ReportRequest normalized() {
        if (kind == null) {
            throw new InteractionValidationException("kind", "Укажите вид отчёта: PORTFOLIO, EVENTS или DEMAND");
        }
        ReportFilters normalizedFilters = filters == null ? ReportFilters.none() : filters.normalized();
        if (kind == ReportKind.DEMAND) {
            requireDemandSelection(normalizedFilters);
        } else if (sortBy != null) {
            throw new InteractionValidationException("sortBy", "Сортировка по показателю доступна только для отчёта DEMAND");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        if (groupBy == StatisticsGroupBy.MONTH && kind == ReportKind.PORTFOLIO && periodBasis == PeriodBasis.ACTIVITY) {
            throw new InteractionValidationException(
                    "groupBy",
                    "Группировка по месяцам недоступна для отбора «С событиями в периоде»: у взаимодействия бывают события"
                            + " в разных месяцах. Выберите отбор «Созданные в периоде» или отчёт «События за период»"
            );
        }
        return new ReportRequest(
                kind,
                from,
                to,
                kind == ReportKind.PORTFOLIO ? (periodBasis == null ? PeriodBasis.CREATED : periodBasis) : null,
                normalizedFilters,
                groupBy == null ? normalizedColumns() : List.of(),
                format,
                groupBy,
                kind == ReportKind.DEMAND && sortBy == null ? ReportColumn.APPLICATIONS : sortBy
        );
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
        if (groupBy != null && !DEMAND_GROUPINGS.contains(groupBy)) {
            throw new InteractionValidationException(
                    "groupBy", "Заявки группируются по вузам, ИТ-направлениям, ИТ-программам, ответственным или месяцам"
            );
        }
        if (sortBy != null && !DEMAND_SORTS.contains(sortBy)) {
            throw new InteractionValidationException(
                    "sortBy", "Сортировать можно по заявкам, обучающимся или параллельным потокам"
            );
        }
    }

    public StatisticsRequest statisticsRequest() {
        return new StatisticsRequest(kind, groupBy, from, to, periodBasis, filters);
    }

    public OffsetDateTime fromAt() {
        return from == null ? null : from.atStartOfDay(ZONE).toOffsetDateTime();
    }

    public OffsetDateTime toAt() {
        return to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    public LocalDate runsAsOf() {
        return to == null ? LocalDate.now(ZONE) : to;
    }

    private List<ReportColumn> normalizedColumns() {
        if (columns == null || columns.isEmpty()) {
            return kind.columns();
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
