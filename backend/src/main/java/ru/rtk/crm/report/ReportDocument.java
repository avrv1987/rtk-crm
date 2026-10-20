package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReportDocument(
        ReportRequest request,
        OffsetDateTime generatedAt,
        List<String> notes,
        List<ReportRow> rows,
        Map<ReportColumn, String> columnTitles,
        List<ManagerWorkTotal> groupTotals,
        List<ManagerWorkGroupTotal> productTotals,
        List<ManagerWorkGroupTotal> organizationTotals
) {
    static final String MANAGER_WORK_NOW_TITLE = "Работа менеджеров: текущее состояние";
    private static final String PERIOD_SUFFIX = "за период";
    private static final String ALL_TIME_SUFFIX = "за всё время";
    private static final Map<ReportColumn, String> ALL_TIME_COLUMNS = Map.of(
            ReportColumn.TRANSITIONS, "Переходов за всё время",
            ReportColumn.PERIOD_COMMENTS, "Комментарии за всё время"
    );

    public ReportDocument(
            ReportRequest request,
            OffsetDateTime generatedAt,
            List<String> notes,
            List<ReportRow> rows,
            Map<ReportColumn, String> columnTitles,
            List<ManagerWorkTotal> groupTotals
    ) {
        this(request, generatedAt, notes, rows, columnTitles, groupTotals, List.of(), List.of());
    }

    public ReportDocument(ReportRequest request, OffsetDateTime generatedAt, List<String> notes, List<ReportRow> rows) {
        this(request, generatedAt, notes, rows, Map.of(), List.of());
    }

    public ReportDocument(
            ReportRequest request,
            OffsetDateTime generatedAt,
            List<String> notes,
            List<ReportRow> rows,
            Map<ReportColumn, String> columnTitles
    ) {
        this(request, generatedAt, notes, rows, columnTitles, List.of());
    }

    public String columnTitle(ReportColumn column) {
        if (managerWorkNow() && ALL_TIME_COLUMNS.containsKey(column)) {
            return ALL_TIME_COLUMNS.get(column);
        }
        return columnTitles.getOrDefault(column, column.title(request.kind()));
    }

    public String title() {
        return managerWorkNow() ? MANAGER_WORK_NOW_TITLE : request.kind().title();
    }

    // Подписи итоговых таблиц «Работы менеджеров»: без периода переходы посчитаны за всё время, а не «за период».
    public List<String> totalTitles(List<String> titles) {
        return managerWorkNow() ? titles.stream().map(title -> title.replace(PERIOD_SUFFIX, ALL_TIME_SUFFIX)).toList() : titles;
    }

    // Д-14: «Контроль» в режиме «Сейчас» выгружает «Работу менеджеров» без периода. Это срез на момент формирования,
    // поэтому заголовок и колонки не должны говорить «за период», а переходы и комментарии — за всё время.
    boolean managerWorkNow() {
        return request.kind() == ReportKind.MANAGER_WORK && request.from() == null && request.to() == null;
    }

    public List<ReportColumn> columns() {
        return request.columns();
    }

    public long organizationCount() {
        return rows.stream().map(ReportRow::organizationId).filter(Objects::nonNull).distinct().count();
    }

    public long interactionCount() {
        return rows.stream().map(ReportRow::interactionId).filter(Objects::nonNull).distinct().count();
    }

    public long countWithout(ReportColumn column) {
        return rows.stream().map(column::value).filter(Objects::isNull).count();
    }
}
