package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.ReportRepository.CatalogTable;

@Service
public class ReportService {
    public static final int MAX_PREVIEW_SIZE = 200;

    private static final int MAX_FILLED_MONTHS = 240;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"));
    private static final String DEMAND_NOTE = "Заявки — заявки на обучение с сайта по предложенному контракту для вузов"
            + " в вашей области, отозванные не учитываются; обучающиеся (участия, не уникальные люди) — последний снимок"
            + " потоков Moodle (курсов и групп, сопоставленных с датами начала и окончания), период к ним не применяется;"
            + " параллельные потоки — потоки, чей интервал [начало, окончание) содержит %s (последний день периода, а если"
            + " он не задан — день формирования); «нет данных» — источник не дал значения, это не ноль. Показатели не"
            + " сводятся в единый рейтинг";

    private final ReportRepository repository;
    private final ReportProperties properties;

    public ReportService(ReportRepository repository, ReportProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ReportPreview preview(CrmProfile profile, ReportRequest request, int page, int size) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > MAX_PREVIEW_SIZE) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до " + MAX_PREVIEW_SIZE);
        }
        ReportRequest normalized = request.normalized();
        List<ReportRow> rows = repository.findRows(profile, normalized, size, (long) page * size);
        long total = repository.countRows(profile, normalized);
        OffsetDateTime generatedAt = OffsetDateTime.now();
        return new ReportPreview(
                normalized.kind(),
                generatedAt,
                notes(profile, normalized, generatedAt),
                columnViews(normalized),
                rows.stream().map(row -> values(normalized.columns(), row)).toList(),
                page,
                size,
                total
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportDocument document(CrmProfile profile, ReportRequest request) {
        ReportRequest normalized = request.normalized();
        List<ReportRow> rows = boundedRows(profile, normalized);
        OffsetDateTime generatedAt = OffsetDateTime.now();
        return new ReportDocument(normalized, generatedAt, notes(profile, normalized, generatedAt), rows);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsResult statistics(CrmProfile profile, StatisticsRequest request) {
        if (request.groupBy() == null) {
            throw new InteractionValidationException("groupBy", "Укажите группировку статистики");
        }
        ReportRequest normalized = request.toReportRequest().normalized();
        Map<String, StatisticsResult.Item> items = new LinkedHashMap<>();
        long unknown = 0;
        for (ReportRepository.GroupCount group : repository.countGroups(profile, normalized, request.groupBy())) {
            if (group.key() == null) {
                unknown += group.count();
            } else if (request.groupBy() == StatisticsGroupBy.MONTH) {
                int month = Integer.parseInt(group.key());
                StatisticsResult.Item item = monthItem(YearMonth.of(month / 100, month % 100), group.count());
                items.put(item.key(), item);
            } else {
                items.put(group.key(), new StatisticsResult.Item(group.key(), group.label(), group.count()));
            }
        }
        long total = normalized.kind() == ReportKind.DEMAND
                ? repository.sumApplications(profile, normalized)
                : repository.countRows(profile, normalized);
        OffsetDateTime generatedAt = OffsetDateTime.now();
        return new StatisticsResult(
                normalized.kind(),
                request.groupBy(),
                normalized.kind().unit(),
                normalized.from(),
                normalized.to(),
                normalized.periodBasis(),
                ReportRequest.ZONE.getId(),
                generatedAt,
                normalized.filters(),
                notes(profile, normalized, generatedAt),
                total,
                unknown,
                sortedItems(request.groupBy(), normalized, items),
                StatisticsResult.MAX_CHART_BARS
        );
    }

    @Transactional(readOnly = true)
    public List<ReportManagerOption> managers(CrmProfile profile) {
        return repository.findManagerOptions(profile);
    }

    @Transactional(readOnly = true)
    public List<String> stages(CrmProfile profile) {
        return repository.findStageNames(profile);
    }

    static Map<String, Object> values(List<ReportColumn> columns, ReportRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ReportColumn column : columns) {
            values.put(column.name(), column.value(row));
        }
        return values;
    }

    static List<ReportColumnView> columnViews(ReportRequest request) {
        return request.columns().stream().map(column -> ReportColumnView.of(column, request.kind())).toList();
    }

    private List<ReportRow> boundedRows(CrmProfile profile, ReportRequest request) {
        List<ReportRow> rows = repository.findRows(profile, request, properties.maxRows() + 1, 0);
        if (rows.size() > properties.maxRows()) {
            throw ReportException.rowLimit(properties.maxRows());
        }
        return rows;
    }

    private List<StatisticsResult.Item> sortedItems(
            StatisticsGroupBy groupBy,
            ReportRequest request,
            Map<String, StatisticsResult.Item> items
    ) {
        if (groupBy != StatisticsGroupBy.MONTH) {
            return items.values().stream()
                    .sorted(Comparator.comparingLong(StatisticsResult.Item::count).reversed()
                            .thenComparing(StatisticsResult.Item::label, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
        List<YearMonth> observed = items.keySet().stream().map(YearMonth::parse).sorted().toList();
        YearMonth first = request.from() != null ? YearMonth.from(request.from()) : observed.isEmpty() ? null : observed.getFirst();
        YearMonth last = request.to() != null ? YearMonth.from(request.to()) : observed.isEmpty() ? null : observed.getLast();
        if (first != null && last != null && !first.isAfter(last) && ChronoUnit.MONTHS.between(first, last) < MAX_FILLED_MONTHS) {
            for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
                items.putIfAbsent(MONTH_KEY.format(month), monthItem(month, 0));
            }
        }
        return items.values().stream().sorted(Comparator.comparing(StatisticsResult.Item::key)).toList();
    }

    private List<String> notes(CrmProfile profile, ReportRequest request, OffsetDateTime generatedAt) {
        return List.of(
                "Сформирован: " + ReportColumn.DATE_TIME.format(generatedAt.atZoneSameInstant(ReportRequest.ZONE))
                        + " (часовой пояс " + ReportRequest.ZONE.getId() + ")",
                periodNote(request),
                switch (request.kind()) {
                    case PORTFOLIO -> "Статус работы и ответственный — текущие на момент формирования отчёта";
                    case EVENTS -> "Ответственный — КАМ вуза на момент события; автор — пользователь, выполнивший действие";
                    case DEMAND -> DEMAND_NOTE.formatted(date(request.runsAsOf())) + "; сортировка: "
                            + request.sortBy().title(ReportKind.DEMAND).toLowerCase(Locale.ROOT) + " по убыванию";
                },
                "Фильтры: " + filterDescription(profile, request.filters())
        );
    }

    private String periodNote(ReportRequest request) {
        String selection = switch (request.kind()) {
            case EVENTS -> "события с датой в периоде";
            case DEMAND -> "заявки сайта с датой подачи в периоде";
            case PORTFOLIO -> request.periodBasis() == PeriodBasis.ACTIVITY
                    ? "взаимодействия, у которых есть события в периоде"
                    : "взаимодействия, созданные в периоде";
        };
        if (request.from() == null && request.to() == null) {
            return "Период: не ограничен";
        }
        return "Период: " + date(request.from()) + " – " + date(request.to()) + "; отобраны " + selection;
    }

    private String date(LocalDate value) {
        return value == null ? "…" : DATE.format(value);
    }

    private String filterDescription(CrmProfile profile, ReportFilters filters) {
        List<String> parts = new ArrayList<>();
        if (!filters.organizationIds().isEmpty()) {
            parts.add("вузы: " + labels(
                    repository.findOrganizationNames(profile, filters.organizationIds()),
                    filters.organizationIds(),
                    false
            ));
        }
        if (filters.organizationType() != null) {
            parts.add("тип организации: " + (filters.organizationType() == OrganizationType.UNIVERSITY ? "вуз" : "школа"));
        }
        catalogFilter(parts, "направления", CatalogTable.DIRECTIONS, filters.directionIds(), filters.includeNoDirection());
        catalogFilter(parts, "программы", CatalogTable.PROGRAMS, filters.programIds(), filters.includeNoProgram());
        catalogFilter(parts, "продукты", CatalogTable.PRODUCTS, filters.productIds(), filters.includeNoProduct());
        if (!filters.managerIds().isEmpty() || filters.includeNoManager()) {
            Map<UUID, String> managers = repository.findManagerOptions(profile).stream()
                    .collect(Collectors.toMap(ReportManagerOption::id, ReportManagerOption::displayName));
            parts.add("ответственные: " + labels(managers, filters.managerIds(), filters.includeNoManager()));
        }
        if (!filters.stages().isEmpty()) {
            parts.add("этапы: " + String.join(", ", filters.stages()));
        }
        return parts.isEmpty() ? "не заданы" : String.join("; ", parts);
    }

    private void catalogFilter(List<String> parts, String name, CatalogTable table, List<UUID> ids, boolean includeUnspecified) {
        if (!ids.isEmpty() || includeUnspecified) {
            parts.add(name + ": " + labels(repository.findCatalogNames(table, ids), ids, includeUnspecified));
        }
    }

    private String labels(Map<UUID, String> names, List<UUID> ids, boolean includeUnspecified) {
        List<String> labels = new ArrayList<>(ids.stream().map(names::get).filter(Objects::nonNull).toList());
        long unavailable = ids.size() - labels.size();
        if (unavailable > 0) {
            labels.add("недоступных значений: " + unavailable);
        }
        if (includeUnspecified) {
            labels.add(ReportColumn.UNSPECIFIED.toLowerCase(Locale.ROOT));
        }
        return String.join(", ", labels);
    }

    private static StatisticsResult.Item monthItem(YearMonth month, long count) {
        return new StatisticsResult.Item(MONTH_KEY.format(month), MONTH_LABEL.format(month), count);
    }
}
