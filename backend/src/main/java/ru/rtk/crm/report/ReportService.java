package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationDetails;
import ru.rtk.crm.interaction.InteractionFlag;
import ru.rtk.crm.interaction.StageLmsRole;
import ru.rtk.crm.catalog.CatalogReference;
import ru.rtk.crm.interaction.ProductTransferKind;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.agreement.AgreementRepository;
import ru.rtk.crm.agreement.AgreementRepository.WorkAgreementStatus;
import ru.rtk.crm.work.LmsSignalModels.WorkLearning;
import ru.rtk.crm.work.LmsWorkLearningService;
import ru.rtk.crm.report.ReportRepository.CatalogTable;

@Service
public class ReportService {
    public static final int MAX_PREVIEW_SIZE = 200;

    private static final int MAX_FILLED_MONTHS = 240;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"));
    // Примечания на экране и в файлах держим в одной-двух строках; полное описание методики — в справке.
    private static final String DEMAND_NOTE = "Заявки — с сайта по дате подачи, отозванные не учитываются. Обучающиеся и"
            + " завершившие — участия (не уникальные люди) в потоках Moodle, шедших в периоде, по наблюдению на %s;"
            + " «нет данных» — источник не дал значения, это не ноль; колонки — отдельные шкалы с разными единицами и периодами,"
            + " их отношение не показывает, сколько заявок дошло до обучения; подробнее — в справке";
    private static final String COMPLETION_NOTE = "Доля завершивших — C / (C + N) по участиям с известным статусом"
            + " завершения (C — завершили, N — не завершили), как в «Динамике обучения»; основание — «C из C + N с известным"
            + " статусом; данные по C + N из всех участий», то есть покрытие; поток периода без наблюдения на дату в долю не"
            + " входит и назван в основании, программа только с такими потоками — «нет данных», а не ноль";
    private static final String MARKET_NOTE = "Спрос рынка труда — только для направлений с признаком: вакансии — на последний"
            + " срез периода, тренд — изменение этого числа в процентах за период; «не применяется» и «нет данных» — не ноль,"
            + " в рейтинг спрос не входит";
    private static final String DURATION_NOTE = "Длительность — календарные дни от входа в этап до перехода в следующий;"
            + " время до входа в последний этап — от создания взаимодействия до первого входа в последний этап маршрута, это не"
            + " подтверждённое завершение; медиана и 90-й процентиль — по завершённым прохождениям, их число рядом;"
            + " «нет данных» — в периоде не закончилось ни одного прохождения, это не ноль";
    private static final String EMPLOYEE_ACTION_NOTE = "последнее действие сотрудника — последнее событие взаимодействия без"
            + " автоматических записей источников (Moodle, сайт); «не зафиксировано» — таких действий нет";
    private static final Map<ReportEventType, String> EVENT_TYPE_TITLES = Map.ofEntries(
            Map.entry(ReportEventType.CREATED, "создание"),
            Map.entry(ReportEventType.TRANSITIONED, "переход"),
            Map.entry(ReportEventType.COMMENTED, "комментарий"),
            Map.entry(ReportEventType.STAGES_EDITED, "изменены этапы карточки"),
            Map.entry(ReportEventType.PLAN_UPDATED, "изменён план"),
            Map.entry(ReportEventType.DETAILS_UPDATED, "изменены данные взаимодействия"),
            Map.entry(ReportEventType.STATUS_CHANGED, "изменён статус взаимодействия"),
            Map.entry(ReportEventType.AGREEMENT_UPDATED, "изменены договор и передача"),
            Map.entry(ReportEventType.ATTACHMENT_DELETED, "удалён документ"),
            Map.entry(ReportEventType.STAGE_COMPLETED, "этап отмечен выполненным"),
            Map.entry(ReportEventType.STAGE_COMPLETION_CLEARED, "снята отметка выполнения этапа"),
            Map.entry(ReportEventType.ASSIGNMENT, "назначение, смена и снятие КАМ")
    );

    private static final String MANAGER_WORK_NOTE = "Строка — взаимодействие с вузом. Менеджер — %s; «Требует назначения» — вуз без"
            + " действующего КАМ. Этап на начало и конец периода — по истории переходов. Комментарии — только людей."
            + " Итоги по менеджерам, командам и всего — отдельной таблицей и листом, в число строк не входят."
            + " «Обучение (LMS)» — последние данные Moodle по идущим потокам на момент формирования";
    static final String MILESTONE_NOTE = "Веха — вход в этап с ролью LMS («Обучение преподавателей», «Идут занятия»,"
            + " «Повышение квалификации») или отметка этого этапа выполненным; роль задаётся в шаблоне этапов, поэтому вехи"
            + " одинаковы для разных маршрутов. Одно взаимодействие может достичь нескольких вех, сумма по вехам может быть"
            + " больше итога; «Не указано» — ни одной вехи";
    static final String GROUP_TOTALS_NOTE ="Итоги по продуктам и по вузам — отдельными таблицами и листами. Одно взаимодействие"
            + " может относиться к нескольким продуктам, поэтому сумма строк по продуктам бывает больше «Всего»: «Всего» считается"
            + " по уникальным взаимодействиям";
    private static final String IDLE_NOTE = "Строка — вуз с КАМ без идущего взаимодействия (группа «Без работы» «Контроля»);"
            + " последнее действие сотрудника — последнее событие взаимодействий вуза, кроме автоматических записей источников;"
            + " «не зафиксировано» — событий нет, это не значит, что работы не было";
    private static final Map<ManagerWorkTile, String> TILE_NOTES = Map.of(
            ManagerWorkTile.OVERDUE, "просрочено",
            ManagerWorkTile.THIS_WEEK, "срок на этой неделе",
            ManagerWorkTile.NO_NEXT_STEP, "без шага или срока",
            ManagerWorkTile.ISSUES, "проблемы и риски",
            ManagerWorkTile.AGREEMENT, "договор на согласовании",
            ManagerWorkTile.UNASSIGNED, "вузы без КАМ",
            ManagerWorkTile.STUCK, "на этапе дольше %d дн.",
            ManagerWorkTile.LMS, "сигналы LMS",
            ManagerWorkTile.IDLE, "вузы без идущего взаимодействия"
    );
    private static final String AGREEMENTS_NOTE = "Строка — мероприятие плана договора (договор без мероприятий — одна строка);"
            + " объёмы — числа без ФИО. Обучающиеся — участия в потоках Moodle связанных взаимодействий, пересекающихся с мероприятием"
            + " и периодом, по наблюдению на конец периода; подтверждения — проверенные документы";

    private final ReportRepository repository;
    private final ReportProperties properties;
    private final ModuleProperties modules;
    private final LmsWorkLearningService lmsWorkLearningService;
    private final AgreementRepository agreementRepository;

    public ReportService(ReportRepository repository, ReportProperties properties, ModuleProperties modules,
                         LmsWorkLearningService lmsWorkLearningService, AgreementRepository agreementRepository) {
        this.repository = repository;
        this.properties = properties;
        this.modules = modules;
        this.lmsWorkLearningService = lmsWorkLearningService;
        this.agreementRepository = agreementRepository;
    }

    ReportRequest normalize(ReportRequest request) {
        return request.normalized(modules.enrolmentEnabled());
    }

    @Transactional(readOnly = true)
    public ReportPreview preview(CrmProfile profile, ReportRequest request, int page, int size) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > MAX_PREVIEW_SIZE) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до " + MAX_PREVIEW_SIZE);
        }
        ReportRequest normalized = normalize(request);
        OffsetDateTime generatedAt = OffsetDateTime.now();
        List<ReportRow> rows;
        ManagerWorkRows managerWork = ManagerWorkRows.EMPTY;
        long total;
        if (normalized.kind() == ReportKind.DURATION || normalized.kind() == ReportKind.MANAGER_WORK) {
            List<ReportRow> all;
            if (normalized.kind() == ReportKind.DURATION) {
                all = durationRows(profile, normalized, generatedAt);
            } else {
                managerWork = managerWorkRows(profile, normalized, generatedAt);
                all = managerWork.rows();
            }
            long offset = (long) page * size;
            rows = offset >= all.size() ? List.of() : all.subList((int) offset, (int) Math.min(all.size(), offset + size));
            total = all.size();
        } else {
            rows = repository.findRows(profile, normalized, generatedAt, size, (long) page * size);
            total = repository.countRows(profile, normalized, generatedAt);
        }
        Map<ReportColumn, String> titles = columnTitles(normalized, generatedAt);
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
                total,
                managerWork.totals(),
                managerWork.productTotals(),
                managerWork.organizationTotals()
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportDocument document(CrmProfile profile, ReportRequest request) {
        ReportRequest normalized = normalize(request);
        OffsetDateTime generatedAt = OffsetDateTime.now();
        ManagerWorkRows managerWork = ManagerWorkRows.EMPTY;
        List<ReportRow> rows;
        switch (normalized.kind()) {
            case DURATION -> rows = durationRows(profile, normalized, generatedAt);
            case MANAGER_WORK -> {
                managerWork = managerWorkRows(profile, normalized, generatedAt);
                rows = managerWork.rows();
            }
            default -> rows = repository.findRows(profile, normalized, generatedAt, properties.maxRows() + 1, 0);
        }
        if (rows.size() > properties.maxRows()) {
            throw ReportException.rowLimit(properties.maxRows());
        }
        return new ReportDocument(normalized, generatedAt, notes(profile, normalized, generatedAt), rows,
                columnTitles(normalized, generatedAt), managerWork.totals(), managerWork.productTotals(),
                managerWork.organizationTotals());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsResult statistics(CrmProfile profile, StatisticsRequest request) {
        if (request.groupBy() == null) {
            throw new InteractionValidationException("groupBy", "Укажите группировку статистики");
        }
        ReportRequest normalized = normalize(request.toReportRequest());
        OffsetDateTime generatedAt = OffsetDateTime.now();
        Map<String, StatisticsResult.Item> items = new LinkedHashMap<>();
        long unknown = 0;
        List<ReportRepository.StageRoute> routes = request.groupBy() == StatisticsGroupBy.STAGE
                ? repository.findStageRoutes(profile, normalized, generatedAt)
                : List.of();
        boolean stagesByRoute = routes.size() > 1;
        for (ReportRepository.GroupCount group : repository.countGroups(
                profile, normalized, request.groupBy(), null, generatedAt, stagesByRoute)) {
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
        List<StatisticsResult.Item> sorted = sortedItems(request.groupBy(), normalized, items, routeStages(routes));
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

    // Разбор А05: в отчётах о прошлом этап и ответственный восстанавливаются по истории, а программа, продукты,
    // направление и команда берутся текущими — истории этих принадлежностей в CRM нет. Подпись колонки это называет.
    private static boolean pastWithCurrentFields(ReportRequest request, OffsetDateTime generatedAt) {
        return switch (request.kind()) {
            case SNAPSHOT, EVENTS, DURATION -> true;
            case MANAGER_WORK -> request.filters().tile() != ManagerWorkTile.IDLE && historicalManager(request, generatedAt);
            case PORTFOLIO, DEMAND, AGREEMENTS -> false;
        };
    }

    private static final Map<ReportColumn, String> CURRENT_FIELD_TITLES = Map.of(
            ReportColumn.DIRECTION, "ИТ-направление (текущее)",
            ReportColumn.PROGRAM, "ИТ-программа (текущая)",
            ReportColumn.PRODUCTS, "ИТ-продукты (текущие)",
            ReportColumn.TEAM, "Команда (текущая)"
    );

    private static String fieldTimeNote(ReportRequest request) {
        return switch (request.kind()) {
            case SNAPSHOT -> "Время полей: на дату — этап и ответственный; текущее — ИТ-направление, ИТ-программа, ИТ-продукты"
                    + " и команда";
            case EVENTS -> "Время полей: на момент события — ответственный и этапы перехода; текущее — ИТ-направление,"
                    + " ИТ-программа, ИТ-продукты и команда";
            case DURATION -> "Время полей: прохождения — по истории переходов; текущее — команда и ИТ-программа";
            default -> "Время полей: на конец периода — менеджер и этап; текущее — ИТ-направление, ИТ-программа, ИТ-продукты,"
                    + " команда, следующий шаг и срок, проблемы и риски, договор, обучение (LMS)";
        } + ". Истории программы, продуктов и команды вуза в CRM нет: после их смены отчёт за прошлый период строится"
                + " по новым значениям";
    }

    private Map<ReportColumn, String> columnTitles(ReportRequest request, OffsetDateTime generatedAt) {
        Map<ReportColumn, String> titles = new EnumMap<>(ReportColumn.class);
        if (pastWithCurrentFields(request, generatedAt)) {
            titles.putAll(CURRENT_FIELD_TITLES);
        }
        if (request.kind() == ReportKind.MANAGER_WORK) {
            titles.put(ReportColumn.MANAGER, request.filters().tile() == ManagerWorkTile.IDLE ? "КАМ вуза"
                    : historicalManager(request, generatedAt) ? "Менеджер на конец периода" : "Текущий менеджер");
            return titles;
        }
        if (request.kind() != ReportKind.DEMAND) {
            return titles;
        }
        String snapshot = "на " + date(request.runsAsOf());
        return Map.of(
                ReportColumn.APPLICATIONS, "Заявки (сайт, по дате подачи: " + applicationPeriod(request) + ")",
                ReportColumn.PAID_ORDERS, "Оплаченные заявки (сайт, без ограничения периода)",
                ReportColumn.PAID_STREAMS, "Потоки с оплатами (сайт, без ограничения периода)",
                ReportColumn.PARTICIPANTS, "Обучающиеся в потоках периода (Moodle, участия — не уникальные люди, " + snapshot + ")",
                ReportColumn.LEARNERS_COMPLETED, "Завершили (Moodle, участия, " + snapshot + ")",
                ReportColumn.COMPLETION_SHARE, "Доля завершивших среди известных, % (Moodle, участия, " + snapshot + ")",
                ReportColumn.COMPLETION_BASIS, "Основание доли (Moodle, участия, " + snapshot + ")",
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
        String asOf = date(request.runsAsOf());
        return repository.findLearningObservedAt(profile, request)
                .map(observedAt -> "Данные Moodle на " + asOf + " (МСК); последнее наблюдение — "
                        + ReportColumn.DATE_TIME.format(observedAt.atZoneSameInstant(ReportRequest.ZONE)) + " (МСК)")
                .orElse("Данные Moodle: у потоков периода нет наблюдений на " + asOf)
                + (request.periodNotEnded() ? "; период ещё не закончился, данные взяты на сегодня" : "");
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

    private record ManagerWorkRows(List<ReportRow> rows, List<ManagerWorkTotal> totals, List<ManagerWorkGroupTotal> productTotals,
                                   List<ManagerWorkGroupTotal> organizationTotals) {
        static final ManagerWorkRows EMPTY = new ManagerWorkRows(List.of(), List.of(), List.of(), List.of());
    }

    private ManagerWorkRows managerWorkRows(CrmProfile profile, ReportRequest request, OffsetDateTime generatedAt) {
        if (request.filters().tile() == ManagerWorkTile.IDLE) {
            List<ManagerWorkOrganization> idle = repository.findOrganizationsWithoutActiveWork(profile).stream()
                    .filter(organization -> organization.managerId() != null && organization.selected(request.filters()))
                    .toList();
            return new ManagerWorkRows(ManagerWorkReport.idleRows(idle), ManagerWorkReport.totals(List.of(), idle, generatedAt),
                    List.of(), List.of());
        }
        List<ManagerWorkLine> found = repository.findManagerWork(
                profile, request.filters(), request.from(), request.to(), generatedAt, managerAtPeriodEnd(request)
        );
        List<UUID> foundIds = found.stream().map(ManagerWorkLine::interactionId).toList();
        ManagerWorkTile tile = request.filters().tile();
        boolean withLearning = tile == ManagerWorkTile.LMS
                || request.columns().contains(ReportColumn.LMS_LEARNING) || request.columns().contains(ReportColumn.LMS_CHECK);
        boolean withAgreements = tile == ManagerWorkTile.STUCK
                || request.columns().contains(ReportColumn.AGREEMENT) || request.columns().contains(ReportColumn.AGREEMENT_STATUS);
        Map<UUID, WorkLearning> learning = withLearning ? lmsWorkLearningService.byWork(profile, foundIds) : Map.of();
        Map<UUID, WorkAgreementStatus> agreements = withAgreements ? agreementRepository.findWorkStatuses(foundIds) : null;
        int stuckDays = Objects.requireNonNullElse(request.filters().minDaysOnStage(), ManagerWorkReport.DEFAULT_STUCK_DAYS);
        Set<UUID> agreementOrganizations = tile == ManagerWorkTile.AGREEMENT
                ? agreementRepository.findOrganizationsWithAgreementInProgress(
                        found.stream().map(ManagerWorkLine::organizationId).collect(Collectors.toSet()))
                : Set.of();
        List<ManagerWorkLine> lines = tile == null ? found : found.stream()
                .filter(line -> ManagerWorkReport.matches(line, tile, stuckDays,
                        agreements == null ? null : agreements.get(line.interactionId()), learning.get(line.interactionId()),
                        agreementOrganizations, generatedAt))
                .toList();
        List<UUID> ids = lines.stream().map(ManagerWorkLine::interactionId).toList();
        Map<UUID, List<String>> products = repository.findManagerWorkProducts(ids);
        return new ManagerWorkRows(
                ManagerWorkReport.rows(
                        lines,
                        products,
                        repository.findManagerWorkComments(ids, request.from(), request.to()),
                        learning,
                        request.columns().contains(ReportColumn.AGREEMENT) || request.columns().contains(ReportColumn.AGREEMENT_STATUS)
                                ? agreements : null,
                        generatedAt
                ),
                ManagerWorkReport.totals(lines, idleOrganizations(profile, request), generatedAt),
                ManagerWorkReport.productTotals(lines, products, generatedAt),
                ManagerWorkReport.organizationTotals(lines, generatedAt)
        );
    }

    // Без явного выбора отчёт «Работа менеджеров» показывает КАМ на конец периода, как до появления режимов «Контроля».
    private static boolean managerAtPeriodEnd(ReportRequest request) {
        return request.filters().managerAt() != ManagerAt.CURRENT;
    }

    private static boolean historicalManager(ReportRequest request, OffsetDateTime generatedAt) {
        return managerAtPeriodEnd(request) && request.toAt() != null && request.toAt().isBefore(generatedAt);
    }

    // Итоги файла считают вузы без идущей работы так же, как экран «Контроля». С плиткой итоги описывают только
    // отобранные строки, а вуз без работы ни под одну плитку не подходит.
    private List<ManagerWorkOrganization> idleOrganizations(CrmProfile profile, ReportRequest request) {
        if (request.filters().tile() != null || !ManagerWorkOrganization.counted(request.filters(), request.from(), request.to())) {
            return List.of();
        }
        return repository.findOrganizationsWithoutActiveWork(profile).stream()
                .filter(organization -> organization.selected(request.filters()))
                .toList();
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
        List<StatisticsResult.Series> ordered = counts.entrySet().stream()
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
        return withOthers(ordered, months.size());
    }

    // Линии сверх пяти крупнейших сливаются в «Остальные»: иначе файл графика не помещает ряды и отказывает.
    // «Не указано» остаётся отдельной линией и в «Остальные» не входит.
    static List<StatisticsResult.Series> withOthers(List<StatisticsResult.Series> ordered, int months) {
        List<StatisticsResult.Series> named = ordered.stream().filter(series -> !series.unspecified()).toList();
        if (named.size() <= StatisticsResult.MAX_NAMED_SERIES) {
            return ordered;
        }
        List<StatisticsResult.Series> rest = named.subList(StatisticsResult.MAX_NAMED_SERIES, named.size());
        long[] sum = new long[months];
        rest.forEach(series -> {
            for (int index = 0; index < months; index++) {
                sum[index] += series.counts().get(index);
            }
        });
        List<StatisticsResult.Series> result = new ArrayList<>(named.subList(0, StatisticsResult.MAX_NAMED_SERIES));
        result.add(new StatisticsResult.Series("others", "Остальные (" + rest.size() + ")", false,
                Arrays.stream(sum).boxed().toList(), true));
        ordered.stream().filter(StatisticsResult.Series::unspecified).forEach(result::add);
        return result;
    }

    private static YearMonth month(String key) {
        int month = Integer.parseInt(key);
        return YearMonth.of(month / 100, month % 100);
    }

    private List<StatisticsResult.Item> sortedItems(
            StatisticsGroupBy groupBy,
            ReportRequest request,
            Map<String, StatisticsResult.Item> items,
            List<RouteStage> stageRoute
    ) {
        if (groupBy == StatisticsGroupBy.STAGE) {
            return stageItems(items, stageRoute, request.filters().stages());
        }
        // Вехи идут в порядке ролей и показываются все, даже с нулём: «до занятий не дошёл никто» — тоже ответ.
        if (groupBy == StatisticsGroupBy.MILESTONE) {
            return Arrays.stream(StageLmsRole.values())
                    .map(role -> items.getOrDefault(role.name(), new StatisticsResult.Item(role.name(), role.title(), 0)))
                    .toList();
        }
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

    record RouteStage(String key, String label, String name) {
    }

    // Один маршрут в выборке — столбцы по названию этапа в порядке этого маршрута; несколько — группы «шаблон: этап»,
    // маршрут за маршрутом, каждый в своём порядке.
    static List<RouteStage> routeStages(List<ReportRepository.StageRoute> routes) {
        if (routes.size() == 1) {
            return routes.getFirst().stages().stream().map(name -> new RouteStage(name, name, name)).toList();
        }
        return routes.stream()
                .flatMap(route -> route.stages().stream().map(name -> new RouteStage(route.key(name), route.label(name), name)))
                .toList();
    }

    // Этапы идут в порядке маршрута, пустые показаны нулём (звенья цикла видны); этапы вне маршрута — в конце по убыванию.
    static List<StatisticsResult.Item> stageItems(Map<String, StatisticsResult.Item> items, List<RouteStage> route, List<String> selected) {
        List<StatisticsResult.Item> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (RouteStage stage : route) {
            keys.add(stage.key());
            if (selected.isEmpty() || selected.contains(stage.name())) {
                result.add(items.getOrDefault(stage.key(), new StatisticsResult.Item(stage.key(), stage.label(), 0)));
            }
        }
        items.values().stream()
                .filter(item -> !keys.contains(item.key()))

                .sorted(Comparator.comparingLong(StatisticsResult.Item::count).reversed()
                        .thenComparing(StatisticsResult.Item::label, String.CASE_INSENSITIVE_ORDER))
                .forEach(result::add);
        return result;
    }

    private List<String> notes(CrmProfile profile, ReportRequest request, OffsetDateTime generatedAt) {
        List<String> notes = new ArrayList<>(List.of(
                "Сформирован: " + ReportColumn.DATE_TIME.format(generatedAt.atZoneSameInstant(ReportRequest.ZONE))
                        + " (часовой пояс " + ReportRequest.ZONE.getId() + ")",
                periodNote(request, generatedAt),
                switch (request.kind()) {
                    case PORTFOLIO -> "Этап, статус взаимодействия и ответственный — текущие на момент формирования отчёта; дней на этапе —"
                            + " полных суток от последнего входа в текущий этап до момента формирования; " + EMPLOYEE_ACTION_NOTE;
                    case EVENTS -> "Ответственный — КАМ вуза на момент события; автор — пользователь, выполнивший действие;"
                            + (profile.role() == UserRole.LEADER || profile.role() == UserRole.MANAGEMENT
                            ? " назначение, смена и снятие КАМ — строки вуза без взаимодействия, ответственный в них — КАМ"
                            + " после события"
                            : " история назначений КАМ в отчёт КАМ не входит, её видит руководитель команды");
                    case DEMAND -> DEMAND_NOTE.formatted(date(request.runsAsOf()))
                            + "; сортировка: "
                            + request.sortBy().title(ReportKind.DEMAND).toLowerCase(Locale.ROOT) + " по убыванию";
                    case SNAPSHOT -> "Этап — по последнему переходу до конца выбранного дня, ответственный — КАМ вуза на эту"
                            + " дату по истории назначений; ИТ-программа и ИТ-продукты — текущие; " + EMPLOYEE_ACTION_NOTE;
                    case DURATION -> DURATION_NOTE;
                    case AGREEMENTS -> AGREEMENTS_NOTE;
                    case MANAGER_WORK -> request.filters().tile() == ManagerWorkTile.IDLE ? IDLE_NOTE
                            : MANAGER_WORK_NOTE.formatted(historicalManager(request, generatedAt)
                                    ? "ведущий вуз на конец периода по истории назначений («За период»)"
                                    : managerAtPeriodEnd(request)
                                    ? "текущий ответственный за вуз (период не закончился или не задан)"
                                    : "текущий ответственный за вуз («Сейчас»)");
                },
                "Фильтры: " + filterDescription(profile, request.filters()) + "; архивные вузы не входят"
        ));
        if (request.kind() == ReportKind.MANAGER_WORK && request.filters().tile() != ManagerWorkTile.IDLE) {
            notes.add(notes.size() - 1, GROUP_TOTALS_NOTE);
        }
        if (request.groupBy() == StatisticsGroupBy.MILESTONE) {
            notes.add(notes.size() - 1, MILESTONE_NOTE);
        }
        if (pastWithCurrentFields(request, generatedAt)) {
            notes.add(notes.size() - 1, fieldTimeNote(request));
        }
        if (request.filters().tile() != null) {
            notes.add("Отбор плиткой «Контроля»: " + TILE_NOTES.get(request.filters().tile()).formatted(
                    Objects.requireNonNullElse(request.filters().minDaysOnStage(), ManagerWorkReport.DEFAULT_STUCK_DAYS)));
        }
        if (request.kind() == ReportKind.DEMAND) {
            notes.add(2, learningNote(profile, request));
            if (request.columns().contains(ReportColumn.MARKET_VACANCIES) || request.columns().contains(ReportColumn.MARKET_TREND)) {
                notes.add(3, marketNote());
            }
            // Перед строкой «Фильтры», сразу после общего примечания отчёта.
            if (request.columns().contains(ReportColumn.COMPLETION_SHARE) || request.columns().contains(ReportColumn.COMPLETION_BASIS)) {
                notes.add(notes.size() - 1, COMPLETION_NOTE);
            }
        }
        return List.copyOf(notes);
    }

    private String marketNote() {
        return MARKET_NOTE + repository.findMarketSource()
                .map(source -> "; источник данных — " + source.dataSource() + ", последний срез на " + date(source.asOf()))
                .orElse("; срезов источника рынка труда в CRM пока нет");
    }

    private String periodNote(ReportRequest request, OffsetDateTime generatedAt) {
        if (request.kind() == ReportKind.SNAPSHOT) {
            return "Состояние на " + date(request.asOf()) + " (конец дня по московскому времени): взаимодействия, созданные"
                    + " до конца этого дня";
        }
        String selection = switch (request.kind()) {
            case EVENTS -> "события с датой в периоде";
            case DEMAND -> modules.enrolmentEnabled()
                    ? "заявки сайта с датой подачи в периоде; к оплатам период не применяется"
                    : "заявки сайта с датой подачи в периоде";
            case PORTFOLIO -> switch (request.periodBasis()) {
                case ACTIVITY -> "взаимодействия, у которых есть события в периоде";
                case ACTIVE -> "взаимодействия, созданные до конца периода и не завершённые к его началу (статуса"
                        + " завершения в CRM нет, поэтому незавершёнными считаются все)";
                case CREATED -> "взаимодействия, созданные в периоде";
            };
            case DURATION -> "прохождения этапов, закончившиеся в периоде, и входы в последний этап в периоде; «на конец периода» — на "
                    + ReportColumn.DATE_TIME.format(durationCutoff(request, generatedAt).atZoneSameInstant(ReportRequest.ZONE));
            case SNAPSHOT -> throw new IllegalStateException("Snapshot has no period");
            case MANAGER_WORK -> "взаимодействия, созданные до конца периода, которые сейчас активны или имели события в периоде";
            case AGREEMENTS -> "мероприятия, сроки которых (фактические, иначе плановые, иначе срок договора)"
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
        catalogFilter(parts, "виды взаимодействия", CatalogTable.INTERACTION_KINDS, filters.kindIds(), false);

        if (!filters.managerIds().isEmpty() || filters.includeNoManager()) {
            Map<UUID, String> managers = repository.findManagerOptions(profile).stream()
                    .collect(Collectors.toMap(ReportManagerOption::id, ReportManagerOption::displayName));
            parts.add("ответственные: " + labels(managers, filters.managerIds(), filters.includeNoManager()));
        }
        if (!filters.stages().isEmpty()) {
            parts.add("этапы: " + String.join(", ", filters.stages()));
        }
        if (!filters.workStatuses().isEmpty()) {
            parts.add("состояние взаимодействия: " + filters.workStatuses().stream()
                    .map(InteractionWorkStatus::label)
                    .collect(Collectors.joining(", ")));
        }
        if (!filters.flags().isEmpty()) {
            parts.add("отметки взаимодействия: " + filters.flags().stream()
                    .map(InteractionFlag::label)
                    .collect(Collectors.joining(", ")));
        }
        agreementFilter(parts, filters.agreement());
        if (filters.minDaysOnStage() != null) {
            parts.add("на этапе дольше " + filters.minDaysOnStage() + " дн.");
        }
        if (filters.change() != null) {
            parts.add("изменения за период: " + switch (filters.change()) {
                case MOVED -> "перешли на другой этап";
                case NEW -> "новые взаимодействия";
                case COMPLETED -> "завершённые";
            });
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
