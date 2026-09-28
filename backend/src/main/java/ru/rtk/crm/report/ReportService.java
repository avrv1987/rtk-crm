package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
import ru.rtk.crm.catalog.OrganizationDetails;
import ru.rtk.crm.interaction.InteractionFlag;
import ru.rtk.crm.catalog.CatalogReference;
import ru.rtk.crm.interaction.ProductTransferKind;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.report.ReportRepository.CatalogTable;

@Service
public class ReportService {
    public static final int MAX_PREVIEW_SIZE = 200;

    private static final int MAX_FILLED_MONTHS = 240;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"));
    private static final String DEMAND_NOTE = "Заявки — заявки на обучение с сайта по предложенному контракту для вузов"
            + " в вашей области, отозванные не учитываются, период — по дате подачи; обучающиеся и завершившие (участия, не"
            + " уникальные люди) — потоки занятий студентов в Moodle (курсы и группы, сопоставленные с датами начала и"
            + " окончания), которые шли в периоде хотя бы один день; число потока — наблюдение из истории, действовавшее на"
            + " конец дня %1$s (последнее изменение чисел не позже этой даты; у закрытого потока — последнее до окончания),"
            + " поток без наблюдения к этой дате — «нет данных»; потоки обучения преподавателей не учитываются; завершившие —"
            + " сумма по потокам, где Moodle отслеживает завершение; параллельные потоки — потоки, чей интервал [начало,"
            + " окончание) содержит %1$s (последний день периода, а если он не задан — день формирования); оплаченные заявки — различные"
            + " номера оплаченных заявок физлиц на открытые курсы (служебная организация «Открытый набор (физлица)», видна"
            + " всем КАМ и руководителям), потоки с оплатами — различные пары курса и номера потока; период к оплатам не"
            + " применяется: сайт не передаёт дату оплаты, поэтому они показаны за всё время; с заявками и данными Moodle"
            + " оплаты не складываются; «нет данных» — источник не дал значения, это не ноль. Показатели не сводятся в единый"
            + " рейтинг";
    private static final String DURATION_NOTE = "Длительность — календарные дни от входа в этап до перехода в следующий"
            + " по истории переходов; цикл — от создания работы до первого входа в последний этап её маршрута (статуса"
            + " «Завершена» в CRM нет); команда и ИТ-программа — текущие; «" + DurationReport.ALL_PROGRAMS + "» — итог"
            + " команды; «нет данных» — в периоде не завершилось ни одного прохождения, это не ноль";
    private static final Map<ReportEventType, String> EVENT_TYPE_TITLES = Map.ofEntries(
            Map.entry(ReportEventType.CREATED, "создание"),
            Map.entry(ReportEventType.TRANSITIONED, "переход"),
            Map.entry(ReportEventType.COMMENTED, "комментарий"),
            Map.entry(ReportEventType.STAGES_EDITED, "изменены этапы карточки"),
            Map.entry(ReportEventType.PLAN_UPDATED, "изменён план"),
            Map.entry(ReportEventType.DETAILS_UPDATED, "изменены данные работы"),
            Map.entry(ReportEventType.STATUS_CHANGED, "изменён статус работы"),
            Map.entry(ReportEventType.AGREEMENT_UPDATED, "изменены договор и передача"),
            Map.entry(ReportEventType.ATTACHMENT_DELETED, "удалён документ"),
            Map.entry(ReportEventType.STAGE_COMPLETED, "этап отмечен выполненным"),
            Map.entry(ReportEventType.STAGE_COMPLETION_CLEARED, "снята отметка выполнения этапа"),
            Map.entry(ReportEventType.ASSIGNMENT, "назначение, смена и снятие КАМ")
    );

    private static final String AGREEMENTS_NOTE = "Строка — мероприятие плана соглашения; соглашение без мероприятий"
            + " выводится одной строкой. Ответственный — ответственный за мероприятие. Объёмы указаны числом без ФИО;"
            + " обучающиеся (Moodle) — сумма участий по наблюдениям потоков Moodle для ИТ-программ связанных работ,"
            + " действовавшим на конец последнего дня периода (без него — дня формирования), только потоки, чьи даты"
            + " пересекаются со сроками мероприятия и с периодом отчёта."
            + " Подтверждения — проверенные документы, привязанные к мероприятию, ссылки открываются после входа в CRM";

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
        OffsetDateTime generatedAt = OffsetDateTime.now();
        List<ReportRow> rows;
        long total;
        if (normalized.kind() == ReportKind.DURATION) {
            List<ReportRow> all = durationRows(profile, normalized, generatedAt);
            long offset = (long) page * size;
            rows = offset >= all.size() ? List.of() : all.subList((int) offset, (int) Math.min(all.size(), offset + size));
            total = all.size();
        } else {
            rows = repository.findRows(profile, normalized, generatedAt, size, (long) page * size);
            total = repository.countRows(profile, normalized, generatedAt);
        }
        Map<ReportColumn, String> titles = columnTitles(profile, normalized);
        return new ReportPreview(
                normalized.kind(),
                generatedAt,
                notes(profile, normalized, generatedAt),
                normalized.columns().stream()
                        .map(column -> ReportColumnView.of(column, titles.getOrDefault(column, column.title(normalized.kind()))))
                        .toList(),
                rows.stream().map(row -> values(normalized.columns(), row)).toList(),
                page,
                size,
                total
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportDocument document(CrmProfile profile, ReportRequest request) {
        ReportRequest normalized = request.normalized();
        OffsetDateTime generatedAt = OffsetDateTime.now();
        List<ReportRow> rows = normalized.kind() == ReportKind.DURATION
                ? durationRows(profile, normalized, generatedAt)
                : repository.findRows(profile, normalized, generatedAt, properties.maxRows() + 1, 0);
        if (rows.size() > properties.maxRows()) {
            throw ReportException.rowLimit(properties.maxRows());
        }
        return new ReportDocument(normalized, generatedAt, notes(profile, normalized, generatedAt), rows,
                columnTitles(profile, normalized));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsResult statistics(CrmProfile profile, StatisticsRequest request) {
        if (request.groupBy() == null) {
            throw new InteractionValidationException("groupBy", "Укажите группировку статистики");
        }
        ReportRequest normalized = request.toReportRequest().normalized();
        OffsetDateTime generatedAt = OffsetDateTime.now();
        Map<String, StatisticsResult.Item> items = new LinkedHashMap<>();
        long unknown = 0;
        for (ReportRepository.GroupCount group : repository.countGroups(profile, normalized, request.groupBy(), null, generatedAt)) {
            if (group.key() == null) {
                unknown += group.count();
            } else if (request.groupBy() == StatisticsGroupBy.MONTH) {
                StatisticsResult.Item item = monthItem(month(group.key()), group.count());
                items.put(item.key(), item);
            } else {
                items.put(group.key(), new StatisticsResult.Item(group.key(), group.label(), group.count()));
            }
        }
        long total = normalized.kind() == ReportKind.DEMAND
                ? repository.sumApplications(profile, normalized)
                : repository.countRows(profile, normalized, generatedAt);
        List<StatisticsResult.Item> sorted = sortedItems(request.groupBy(), normalized, items);
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
                sorted,
                StatisticsResult.MAX_CHART_BARS,
                normalized.asOf(),
                normalized.seriesBy(),
                normalized.seriesBy() == null ? List.of() : series(profile, normalized, sorted, generatedAt),
                StatisticsResult.MAX_CHART_SERIES
        );
    }

    @Transactional(readOnly = true)
    public List<ReportManagerOption> managers(CrmProfile profile) {
        return repository.findManagerOptions(profile);
    }

    @Transactional(readOnly = true)
    public List<CatalogReference> vendors() {
        return repository.findVendorOptions();
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

    private Map<ReportColumn, String> columnTitles(CrmProfile profile, ReportRequest request) {
        if (request.kind() != ReportKind.DEMAND) {
            return Map.of();
        }
        String snapshot = "на " + date(request.runsAsOf());
        return Map.of(
                ReportColumn.APPLICATIONS, "Заявки (сайт, " + applicationPeriod(request) + ")",
                ReportColumn.PAID_ORDERS, "Оплаченные заявки (сайт, за всё время: период не применяется)",
                ReportColumn.PAID_STREAMS, "Потоки с оплатами (сайт, за всё время: период не применяется)",
                ReportColumn.PARTICIPANTS, "Обучающиеся (Moodle, " + snapshot + ")",
                ReportColumn.LEARNERS_COMPLETED, "Завершили (Moodle, " + snapshot + ")",
                ReportColumn.PARALLEL_RUNS, "Параллельные потоки (Moodle, на " + date(request.runsAsOf()) + ")"
        );
    }

    private String applicationPeriod(ReportRequest request) {
        if (request.from() == null && request.to() == null) {
            return "весь период";
        }
        if (request.from() == null) {
            return "по " + date(request.to());
        }
        return request.to() == null ? "с " + date(request.from()) : date(request.from()) + "–" + date(request.to());
    }

    private String learningNote(CrmProfile profile, ReportRequest request) {
        return repository.findLearningObservedAt(profile, request)
                .map(observedAt -> "Данные Moodle: наблюдения потоков, действовавшие на конец дня " + date(request.runsAsOf())
                        + " (МСК); последнее изменение чисел среди них — "
                        + ReportColumn.DATE_TIME.format(observedAt.atZoneSameInstant(ReportRequest.ZONE)) + " (МСК)")
                .orElse("Данные Moodle: у потоков занятий студентов этого периода нет наблюдений на конец дня "
                        + date(request.runsAsOf()))
                + "; период заявок: " + applicationPeriod(request);
    }

    private List<ReportRow> durationRows(CrmProfile profile, ReportRequest request, OffsetDateTime generatedAt) {
        OffsetDateTime cutoff = durationCutoff(request, generatedAt);
        return DurationReport.rows(
                repository.findStageEntries(profile, request, cutoff),
                request.filters().stages(),
                request.fromAt(),
                cutoff
        );
    }

    private static OffsetDateTime durationCutoff(ReportRequest request, OffsetDateTime generatedAt) {
        return request.toAt() == null || request.toAt().isAfter(generatedAt) ? generatedAt : request.toAt();
    }

    private List<StatisticsResult.Series> series(
            CrmProfile profile,
            ReportRequest request,
            List<StatisticsResult.Item> months,
            OffsetDateTime generatedAt
    ) {
        Map<String, Integer> positions = new HashMap<>();
        for (int index = 0; index < months.size(); index++) {
            positions.put(months.get(index).key(), index);
        }
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (ReportRepository.GroupCount group
                : repository.countGroups(profile, request, StatisticsGroupBy.MONTH, request.seriesBy(), generatedAt)) {
            String key = Objects.requireNonNullElse(group.seriesKey(), "");
            labels.put(key, group.seriesKey() == null ? ReportColumn.UNSPECIFIED : group.seriesLabel());
            counts.computeIfAbsent(key, ignored -> new long[months.size()])[positions.get(MONTH_KEY.format(month(group.key())))]
                    += group.count();
        }
        return counts.entrySet().stream()
                .map(entry -> new StatisticsResult.Series(
                        entry.getKey().isEmpty() ? "unspecified" : entry.getKey(),
                        labels.get(entry.getKey()),
                        entry.getKey().isEmpty(),
                        Arrays.stream(entry.getValue()).boxed().toList()
                ))
                .sorted(Comparator.comparing(StatisticsResult.Series::unspecified)
                        .thenComparing(series -> -series.counts().stream().mapToLong(Long::longValue).sum())
                        .thenComparing(StatisticsResult.Series::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static YearMonth month(String key) {
        int month = Integer.parseInt(key);
        return YearMonth.of(month / 100, month % 100);
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
        List<String> notes = new ArrayList<>(List.of(
                "Сформирован: " + ReportColumn.DATE_TIME.format(generatedAt.atZoneSameInstant(ReportRequest.ZONE))
                        + " (часовой пояс " + ReportRequest.ZONE.getId() + ")",
                periodNote(request, generatedAt),
                switch (request.kind()) {
                    case PORTFOLIO -> "Этап, статус работы и ответственный — текущие на момент формирования отчёта; дней на этапе —"
                            + " полных суток от последнего входа в текущий этап до момента формирования";
                    case EVENTS -> "Ответственный — КАМ вуза на момент события; автор — пользователь, выполнивший действие;"
                            + (profile.role() == UserRole.LEADER || profile.role() == UserRole.MANAGEMENT
                            ? " назначение, смена и снятие КАМ — строки вуза без взаимодействия, ответственный в них — КАМ"
                            + " после события"
                            : " история назначений КАМ в отчёт КАМ не входит, её видит руководитель команды");
                    case DEMAND -> DEMAND_NOTE.formatted(date(request.runsAsOf())) + "; сортировка: "
                            + request.sortBy().title(ReportKind.DEMAND).toLowerCase(Locale.ROOT) + " по убыванию";
                    case SNAPSHOT -> "Этап — по последнему переходу до конца выбранного дня, ответственный — КАМ вуза на эту"
                            + " дату по истории назначений; ИТ-программа и ИТ-продукты — текущие";
                    case DURATION -> DURATION_NOTE;
                    case AGREEMENTS -> AGREEMENTS_NOTE;
                },
                "Фильтры: " + filterDescription(profile, request.filters())
        ));
        if (request.kind() == ReportKind.DEMAND) {
            notes.add(2, learningNote(profile, request));
        }
        return List.copyOf(notes);
    }

    private String periodNote(ReportRequest request, OffsetDateTime generatedAt) {
        if (request.kind() == ReportKind.SNAPSHOT) {
            return "Состояние на " + date(request.asOf()) + " (конец дня по московскому времени): взаимодействия, созданные"
                    + " до конца этого дня";
        }
        String selection = switch (request.kind()) {
            case EVENTS -> "события с датой в периоде";
            case DEMAND -> "заявки сайта с датой подачи в периоде; к оплатам период не применяется";
            case PORTFOLIO -> switch (request.periodBasis()) {
                case ACTIVITY -> "взаимодействия, у которых есть события в периоде";
                case ACTIVE -> "взаимодействия, созданные до конца периода и не завершённые к его началу (статуса"
                        + " завершения в CRM нет, поэтому незавершёнными считаются все)";
                case CREATED -> "взаимодействия, созданные в периоде";
            };
            case DURATION -> "прохождения этапов и циклы, завершённые в периоде; «на конец периода» — на "
                    + ReportColumn.DATE_TIME.format(durationCutoff(request, generatedAt).atZoneSameInstant(ReportRequest.ZONE));
            case SNAPSHOT -> throw new IllegalStateException("Snapshot has no period");
            case AGREEMENTS -> "мероприятия, сроки которых (фактические, иначе плановые, иначе срок соглашения)"
                    + " пересекаются с периодом";
        };
        if (request.from() == null && request.to() == null) {
            return request.kind() == ReportKind.DURATION ? "Период: не ограничен; отобраны " + selection : "Период: не ограничен";
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
            parts.add("тип организации: " + OrganizationDetails.typeLabel(filters.organizationType()).toLowerCase(Locale.ROOT));
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
        if (!filters.workStatuses().isEmpty()) {
            parts.add("состояние работы: " + filters.workStatuses().stream()
                    .map(InteractionWorkStatus::label)
                    .collect(Collectors.joining(", ")));
        }
        if (!filters.flags().isEmpty()) {
            parts.add("отметки работы: " + filters.flags().stream()
                    .map(InteractionFlag::label)
                    .collect(Collectors.joining(", ")));
        }
        agreementFilter(parts, filters.agreement());
        if (filters.minDaysOnStage() != null) {
            parts.add("на этапе дольше " + filters.minDaysOnStage() + " дн.");
        }
        if (!filters.eventTypes().isEmpty()) {
            parts.add("виды событий: " + filters.eventTypes().stream().map(EVENT_TYPE_TITLES::get).collect(Collectors.joining(", ")));
        }
        return parts.isEmpty() ? "не заданы" : String.join("; ", parts);
    }

    private void agreementFilter(List<String> parts, ReportAgreementFilters agreement) {
        catalogFilter(parts, "вендоры", CatalogTable.VENDORS, agreement.vendorIds(), false);
        if (agreement.licenseSigned() != null) {
            parts.add("лицензия: " + (agreement.licenseSigned() ? "подписана" : "не подписана или не указано"));
        }
        if (agreement.licenseExpiresBy() != null) {
            parts.add("лицензия истекает до: " + agreement.licenseExpiresBy() + " г. включительно");
        }
        if (!agreement.notTransferred().isEmpty()) {
            parts.add("не передано: " + agreement.notTransferred().stream()
                    .map(ProductTransferKind::title)
                    .collect(Collectors.joining(", ")));
        }
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
