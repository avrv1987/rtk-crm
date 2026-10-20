package ru.rtk.crm.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.CatalogReference;
import ru.rtk.crm.agreement.AgreementModels.ActivityStatus;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementRepository;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionMarks;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.StageLmsRole;
import ru.rtk.crm.interaction.InteractionIssueRepository;
import ru.rtk.crm.interaction.ProductAgreementRules;
import ru.rtk.crm.interaction.WorkStatusAt;

@Repository
public class ReportRepository {
    private static final int PRODUCT_BATCH_SIZE = 500;
    private static final String NO_ROWS = "1 = 0";
    // Записи источников (заявки сайта, наблюдения Moodle) создаются командой APPLY_SOURCE_RECORD от имени служебного профиля:
    // это не действие сотрудника, поэтому последнее действие и комментарии людей считаются без них.
    private static final String EMPLOYEE_EVENT = " AND NOT EXISTS (SELECT 1 FROM command_idempotency_records source_command"
            + " WHERE source_command.id = le.command_id AND source_command.operation = 'APPLY_SOURCE_RECORD')";
    private static final String MARKS = "i.work_status, i.work_status_reason, i.waiting_on, i.waiting_note,\n"
            + InteractionIssueRepository.MARK_COLUMNS + ",\n";
    private static final String STAGE_ENTERED = """
            COALESCE((SELECT MAX(ce.occurred_at) FROM interaction_events ce
                      WHERE ce.interaction_id = i.id AND ce.stage_id = i.current_stage_id
                        AND ce.type IN ('CREATED', 'TRANSITIONED')), i.created_at)""";
    private static final String NO_STAGE_ENTERED = "CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS stage_entered_at,\n";

    private static final String EVENT_NULLS = """
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS event_at,
                   CAST(NULL AS VARCHAR(32)) AS event_type,
                   CAST(NULL AS VARCHAR(200)) AS from_stage_name,
                   CAST(NULL AS VARCHAR(4000)) AS event_comment,
                   CAST(NULL AS VARCHAR(200)) AS author_name
            """;

    private static final String PORTFOLIO_SELECT = """
            SELECT i.id AS interaction_id, i.title AS interaction_title,
                   o.id AS organization_id, o.name AS organization_name,
                   d.id AS direction_id, d.name AS direction_name,
                   p.id AS program_id, p.name AS program_name,
                   s.name AS stage_name,
            """ + MARKS + """
                   o.owner_manager_id AS manager_id, m.display_name AS manager_name,
                   i.created_at, i.next_action, i.next_action_at,
                   (SELECT MAX(le.occurred_at) FROM interaction_events le WHERE le.interaction_id = i.id
            """ + EMPLOYEE_EVENT + """
                   ) AS last_event_at,
            """ + STAGE_ENTERED + " AS stage_entered_at,\n" + EVENT_NULLS;

    private static final String PORTFOLIO_FROM = """
            FROM interactions i
            JOIN organizations o ON o.id = i.organization_id
            JOIN interaction_stages s ON s.id = i.current_stage_id AND s.interaction_id = i.id
            LEFT JOIN crm_user_profiles m ON m.id = o.owner_manager_id
            LEFT JOIN programs p ON p.id = i.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String SNAPSHOT_SELECT = """
            SELECT i.id AS interaction_id, i.title AS interaction_title,
                   o.id AS organization_id, o.name AS organization_name,
                   d.id AS direction_id, d.name AS direction_name,
                   p.id AS program_id, p.name AS program_name,
                   sn.stage_name AS stage_name,
            """ + MARKS + """
                   sn.manager_id AS manager_id, m.display_name AS manager_name,
                   i.created_at, i.next_action, i.next_action_at,
                   sn.last_event_at AS last_event_at,
            """ + NO_STAGE_ENTERED + EVENT_NULLS;

    // Срез на дату собирается по истории: этап — из последнего события до границы, менеджер — владелец до
    // первой передачи после границы, иначе текущий. Работы, созданные позже границы, в срез не входят.
    private static final String SNAPSHOT_FROM = """
            FROM interactions i
            JOIN organizations o ON o.id = i.organization_id
            JOIN (
                SELECT si.id AS interaction_id,
                       (SELECT se.stage_name_snapshot FROM interaction_events se
                        WHERE se.interaction_id = si.id AND se.occurred_at < :asOfAt
                          AND se.type IN ('CREATED', 'TRANSITIONED', 'STAGES_EDITED', 'PLAN_UPDATED')
                        ORDER BY se.occurred_at DESC, se.version DESC LIMIT 1) AS stage_name,
                       CASE WHEN EXISTS (SELECT 1 FROM organization_assignment_events fa
                                         WHERE fa.organization_id = si.organization_id AND fa.occurred_at >= :asOfAt)
                            THEN (SELECT fa.previous_owner_manager_id FROM organization_assignment_events fa
                                  WHERE fa.organization_id = si.organization_id AND fa.occurred_at >= :asOfAt
                                  ORDER BY fa.occurred_at, fa.version LIMIT 1)
                            ELSE so.owner_manager_id END AS manager_id,
                       (SELECT MAX(le.occurred_at) FROM interaction_events le
                        WHERE le.interaction_id = si.id AND le.occurred_at < :asOfAt
            """ + EMPLOYEE_EVENT + """
                       ) AS last_event_at
                FROM interactions si
                JOIN organizations so ON so.id = si.organization_id
                WHERE si.created_at < :asOfAt
            ) sn ON sn.interaction_id = i.id
            LEFT JOIN crm_user_profiles m ON m.id = sn.manager_id
            LEFT JOIN programs p ON p.id = i.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String EVENTS_SELECT = """
            SELECT i.id AS interaction_id, i.title AS interaction_title,
                   o.id AS organization_id, o.name AS organization_name,
                   d.id AS direction_id, d.name AS direction_name,
                   p.id AS program_id, p.name AS program_name,
                   e.stage_name_snapshot AS stage_name,
            """ + MARKS + """
                   e.owner_manager_id_snapshot AS manager_id, m.display_name AS manager_name,
                   i.created_at, i.next_action, i.next_action_at,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS last_event_at,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS stage_entered_at,
                   e.occurred_at AS event_at,
                   e.type AS event_type,
                   e.from_stage_name_snapshot AS from_stage_name,
                   %s AS event_comment,
                   a.display_name AS author_name,
                   e.id AS event_id
            """.formatted(InteractionRepository.EVENT_COMMENT);

    private static final String EVENTS_FROM = """
            FROM interaction_events e
            JOIN interactions i ON i.id = e.interaction_id
            JOIN organizations o ON o.id = i.organization_id
            JOIN crm_user_profiles a ON a.id = e.actor_profile_id
            LEFT JOIN crm_user_profiles m ON m.id = e.owner_manager_id_snapshot
            LEFT JOIN programs p ON p.id = i.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String ASSIGNMENT_SELECT = """
            SELECT CAST(NULL AS UUID) AS interaction_id, CAST(NULL AS VARCHAR(200)) AS interaction_title,
                   o.id AS organization_id, o.name AS organization_name,
                   CAST(NULL AS UUID) AS direction_id, CAST(NULL AS VARCHAR(200)) AS direction_name,
                   CAST(NULL AS UUID) AS program_id, CAST(NULL AS VARCHAR(200)) AS program_name,
                   CAST(NULL AS VARCHAR(200)) AS stage_name,
                   CAST(NULL AS VARCHAR(16)) AS work_status, CAST(NULL AS VARCHAR(1000)) AS work_status_reason,
                   CAST(NULL AS VARCHAR(16)) AS waiting_on, CAST(NULL AS VARCHAR(500)) AS waiting_note,
                   CAST(NULL AS BIGINT) AS problem_count, CAST(NULL AS BIGINT) AS risk_count,
                   CAST(NULL AS VARCHAR(16)) AS risk_level, CAST(NULL AS VARCHAR(4000)) AS problems,
                   CAST(NULL AS VARCHAR(4000)) AS risks,
                   ae.owner_manager_id AS manager_id, m.display_name AS manager_name,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS created_at, CAST(NULL AS VARCHAR(500)) AS next_action,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS next_action_at,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS last_event_at,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS stage_entered_at,
                   ae.occurred_at AS event_at,
                   CASE WHEN ae.previous_owner_manager_id IS NULL THEN 'ASSIGNED'
                        WHEN ae.owner_manager_id IS NULL THEN 'UNASSIGNED'
                        ELSE 'REASSIGNED' END AS event_type,
                   CAST(NULL AS VARCHAR(200)) AS from_stage_name,
                   COALESCE(ae.previous_owner_manager_display_name, 'КАМ не назначен') || ' → '
                       || COALESCE(ae.new_owner_manager_display_name, 'КАМ не назначен') AS event_comment,
                   ae.actor_display_name AS author_name,
                   ae.id AS event_id
            """;

    private static final String ASSIGNMENT_FROM = """
            FROM organization_assignment_events ae
            JOIN organizations o ON o.id = ae.organization_id
            LEFT JOIN crm_user_profiles m ON m.id = ae.owner_manager_id
            """;

    // Менеджер на выбранный момент: если после него вуз передавали, им был предыдущий владелец первой передачи, иначе текущий.
    private static final String MANAGER_AT = """
            CASE WHEN EXISTS (SELECT 1 FROM organization_assignment_events fa
                              WHERE fa.organization_id = o.id AND fa.occurred_at >= :%1$s)
                 THEN (SELECT fa.previous_owner_manager_id FROM organization_assignment_events fa
                       WHERE fa.organization_id = o.id AND fa.occurred_at >= :%1$s
                       ORDER BY fa.occurred_at, fa.version LIMIT 1)
                 ELSE o.owner_manager_id END""";

    // Последнее действие сотрудника по вузу — любое событие его взаимодействий, кроме автоматических записей источников
    // (заявки сайта, наблюдения Moodle идут командой APPLY_SOURCE_RECORD).
    private static final String LAST_STAFF_ACTION = """
            (SELECT MAX(sa.occurred_at) FROM interaction_events sa JOIN interactions si ON si.id = sa.interaction_id
             WHERE si.organization_id = o.id AND NOT EXISTS (SELECT 1 FROM command_idempotency_records sc
                   WHERE sc.id = sa.command_id AND sc.operation = 'APPLY_SOURCE_RECORD'))""";

    private static final String MANAGER_WORK_FROM = PORTFOLIO_FROM + "JOIN teams t ON t.id = o.team_id\n";
    // Этап на момент времени: последнее событие, менявшее этап, до границы. Название берётся из снимка в событии,
    // поэтому позднейшее переименование этапа не искажает прошлые периоды.
    private static final String STAGE_AT = """
            (SELECT se.stage_name_snapshot FROM interaction_events se
             WHERE se.interaction_id = i.id AND se.occurred_at < :%s
               AND se.type IN ('CREATED', 'TRANSITIONED', 'STAGES_EDITED', 'PLAN_UPDATED')
             ORDER BY se.occurred_at DESC, se.version DESC LIMIT 1)""";

    private static final String DURATION_FROM = """
            FROM interaction_events e
            JOIN interactions i ON i.id = e.interaction_id
            JOIN organizations o ON o.id = i.organization_id
            JOIN teams t ON t.id = o.team_id
            LEFT JOIN interaction_stages st ON st.id = e.stage_id
            LEFT JOIN programs p ON p.id = i.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String PRODUCT_JOIN =
            " LEFT JOIN product_agreements pa ON pa.interaction_id = i.id AND pa.archived_at IS NULL"
                    + " LEFT JOIN products pr ON pr.id = pa.product_id";

    private static final String DEMAND_FROM = """
            FROM source_records r
            JOIN organizations o ON o.id = r.organization_id
            LEFT JOIN crm_user_profiles m ON m.id = o.owner_manager_id
            LEFT JOIN programs p ON p.id = r.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String LEARNING_FROM = """
            FROM learning_snapshots s
            JOIN source_mappings sm ON sm.id = s.mapping_id AND sm.run_starts_on IS NOT NULL AND sm.run_kind = 'STUDENTS'
            JOIN organizations o ON o.id = s.organization_id
            JOIN programs p ON p.id = s.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            LEFT JOIN learning_observations f ON f.mapping_id = s.mapping_id
                AND f.observed_from = (SELECT MAX(h.observed_from) FROM learning_observations h
                                       WHERE h.mapping_id = s.mapping_id AND h.observed_from < :learningAt)
            """;

    static final String OBSERVATION_IN_FORCE = """
            s.observed_from = (SELECT MAX(h.observed_from) FROM learning_observations h
                               WHERE h.mapping_id = s.mapping_id AND h.observed_from < :learningAt)""";

    private static final String AGREEMENTS_FROM = """
            FROM agreements ag
            JOIN organizations o ON o.id = ag.organization_id
            LEFT JOIN agreement_activities ac ON ac.agreement_id = ag.id
            LEFT JOIN agreement_activity_kinds k ON k.id = ac.kind_id
            LEFT JOIN crm_user_profiles m ON m.id = ac.responsible_profile_id
            """;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final JdbcClient jdbcClient;
    private final OrganizationRepository organizationRepository;
    private final String publicBaseUrl;

    public ReportRepository(
            JdbcClient jdbcClient,
            OrganizationRepository organizationRepository,
            @Value("${app.oidc.public-base-url:}") String publicBaseUrl
    ) {
        this.jdbcClient = jdbcClient;
        this.organizationRepository = organizationRepository;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    public List<ReportRow> findRows(CrmProfile profile, ReportRequest request, OffsetDateTime now, int limit, long offset) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        if (request.kind() == ReportKind.AGREEMENTS) {
            return findAgreementRows(scope.get(), request, limit, offset);
        }
        Selection rows = rowSelection(profile, scope.get(), request, now);
        return withProducts(jdbcClient.sql(rows.sql() + " LIMIT :limit OFFSET :offset")
                .params(rows.parameters())
                .param("limit", limit)
                .param("offset", offset)
                .query((resultSet, rowNumber) -> mapRow(resultSet, now))
                .list());
    }

    public long countRows(CrmProfile profile, ReportRequest request, OffsetDateTime now) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return 0;
        }
        if (request.kind() == ReportKind.AGREEMENTS) {
            Selection selection = agreementSelection(scope.get(), request);
            return jdbcClient.sql("SELECT COUNT(*) " + AGREEMENTS_FROM + selection.sql())
                    .params(selection.parameters())
                    .query(Long.class)
                    .single();
        }
        Selection selection = selection(scope.get(), request, now);
        Selection count = switch (request.kind()) {
            case EVENTS -> {
                Selection assignments = assignmentSelection(profile, scope.get(), request);
                yield new Selection("SELECT (SELECT COUNT(*) " + EVENTS_FROM + selection.sql() + ") + (SELECT COUNT(*) "
                        + ASSIGNMENT_FROM + assignments.sql() + ")", merged(selection, assignments));
            }
            case SNAPSHOT -> new Selection("SELECT COUNT(*) " + SNAPSHOT_FROM + selection.sql(), selection.parameters());
            case PORTFOLIO -> new Selection("SELECT COUNT(*) " + PORTFOLIO_FROM + selection.sql(), selection.parameters());
            case DEMAND, DURATION, AGREEMENTS, MANAGER_WORK -> throw new IllegalArgumentException("Rows of " + request.kind() + " are not counted here");
        };
        return jdbcClient.sql(count.sql())
                .params(count.parameters())
                .query(Long.class)
                .single();
    }

    public long sumApplications(CrmProfile profile, ReportRequest request) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return 0;
        }
        Selection selection = demandSelection(scope.get(), request);
        return jdbcClient.sql("SELECT COALESCE(SUM(r.applications_count), 0) " + DEMAND_FROM + selection.sql())
                .params(selection.parameters())
                .query(Long.class)
                .single();
    }

    public Optional<DemandIndex.Settings> findDemandIndexSettings() {
        return jdbcClient.sql("""
                        SELECT growth_weight, completion_weight, activity_weight, university_weight, payments_weight,
                               min_coverage_percent, small_base
                        FROM demand_index_settings WHERE id = 1
                        """)
                .query((resultSet, rowNumber) -> new DemandIndex.Settings(
                        resultSet.getInt("growth_weight"),
                        resultSet.getInt("completion_weight"),
                        resultSet.getInt("activity_weight"),
                        resultSet.getInt("university_weight"),
                        resultSet.getInt("payments_weight"),
                        resultSet.getInt("min_coverage_percent"),
                        resultSet.getInt("small_base")))
                .optional();
    }

    public Optional<MarketSource> findMarketSource() {
        return jdbcClient.sql("""
                        SELECT data_source, MAX(as_of) AS as_of FROM labor_market_snapshots
                        GROUP BY data_source ORDER BY MAX(as_of) DESC, data_source LIMIT 1
                        """)
                .query((resultSet, rowNumber) -> new MarketSource(
                        resultSet.getString("data_source"), resultSet.getObject("as_of", LocalDate.class)))
                .optional();
    }

    private List<ReportRow> withMarket(List<ReportRow> rows, ReportRequest request) {
        if (rows.isEmpty() || !request.columns().contains(ReportColumn.MARKET_VACANCIES)
                && !request.columns().contains(ReportColumn.MARKET_TREND)) {
            return rows;
        }
        List<UUID> directionIds = rows.stream().map(ReportRow::directionId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, List<Integer>> series = new HashMap<>();
        Map<UUID, Integer> before = new HashMap<>();
        Set<UUID> tracked = new HashSet<>();
        LocalDate fromMonth = request.from() == null ? null : request.from().withDayOfMonth(1);
        if (!directionIds.isEmpty()) {
            jdbcClient.sql("""
                            SELECT d.id AS direction_id, d.labor_market, s.period_month, s.open_vacancies
                            FROM directions d
                            LEFT JOIN labor_market_mappings m ON m.direction_id = d.id
                            LEFT JOIN labor_market_snapshots s ON s.external_code = m.external_code
                                AND s.period_month <= :toMonth
                            WHERE d.id IN (:directionIds)
                            ORDER BY d.id, s.period_month
                            """)
                    .param("directionIds", directionIds)
                    .param("toMonth", request.runsAsOf().withDayOfMonth(1))
                    .query(resultSet -> {
                        UUID directionId = resultSet.getObject("direction_id", UUID.class);
                        if (resultSet.getBoolean("labor_market")) {
                            tracked.add(directionId);
                            Integer vacancies = resultSet.getObject("open_vacancies", Integer.class);
                            List<Integer> months = series.computeIfAbsent(directionId, ignored -> new ArrayList<>());
                            if (vacancies != null && fromMonth != null && resultSet.getObject("period_month", LocalDate.class).isBefore(fromMonth)) {
                                before.put(directionId, vacancies);
                            } else if (vacancies != null) {
                                months.add(vacancies);
                            }
                        }
                    });
        }
        return rows.stream()
                .map(row -> row.withMarket(row.directionId() != null && tracked.contains(row.directionId())
                        ? MarketDemand.of(before.get(row.directionId()), series.get(row.directionId()))
                        : MarketDemand.NOT_TRACKED))
                .toList();
    }

    public Optional<OffsetDateTime> findLearningObservedAt(CrmProfile profile, ReportRequest request) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Selection selection = learningSelection(scope.get(), request);
        return jdbcClient.sql("SELECT MAX(f.observed_from) " + LEARNING_FROM + selection.sql())
                .params(selection.parameters())
                .query((resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class))
                .optional();
    }

    public List<GroupCount> countGroups(
            CrmProfile profile,
            ReportRequest request,
            StatisticsGroupBy groupBy,
            StatisticsGroupBy seriesBy,
            OffsetDateTime now
    ) {
        return countGroups(profile, request, groupBy, seriesBy, now, false);
    }

    // stagesByRoute: при нескольких маршрутах в выборке этап группируется вместе со своим шаблоном — одноимённые этапы
    // разных маршрутов не сливаются в один столбец.
    public List<GroupCount> countGroups(
            CrmProfile profile,
            ReportRequest request,
            StatisticsGroupBy groupBy,
            StatisticsGroupBy seriesBy,
            OffsetDateTime now,
            boolean stagesByRoute
    ) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        ReportKind kind = request.kind();
        if (kind == ReportKind.DEMAND) {
            Selection selection = demandSelection(scope.get(), request);
            String rows = groupSelect(demandGroupColumns(groupBy), seriesBy == null ? null : demandGroupColumns(seriesBy),
                    "r.applications_count") + DEMAND_FROM + selection.sql();
            return grouped(rows, seriesBy != null, "SUM(g.row_value)", selection.parameters());
        }
        Selection selection = selection(scope.get(), request, now);
        boolean products = groupBy == StatisticsGroupBy.PRODUCT || seriesBy == StatisticsGroupBy.PRODUCT;
        GroupColumns group = stagesByRoute && groupBy == StatisticsGroupBy.STAGE ? routeStageColumns(kind) : groupColumns(kind, groupBy);
        String rows = groupSelect(group, seriesBy == null ? null : groupColumns(kind, seriesBy),
                kind == ReportKind.EVENTS ? "e.id" : "i.id")
                + switch (kind) {
                    case EVENTS -> EVENTS_FROM;
                    case SNAPSHOT -> SNAPSHOT_FROM;
                    default -> PORTFOLIO_FROM;
                }
                + (products ? PRODUCT_JOIN : "")
                + (groupBy == StatisticsGroupBy.MILESTONE ? milestoneJoin(kind == ReportKind.SNAPSHOT) : "")
                + selection.sql();
        Map<String, Object> parameters = selection.parameters();
        if (kind == ReportKind.EVENTS) {
            Selection assignments = assignmentSelection(profile, scope.get(), request);
            rows += " UNION ALL " + groupSelect(assignmentGroupColumns(groupBy),
                    seriesBy == null ? null : assignmentGroupColumns(seriesBy), "ae.id") + ASSIGNMENT_FROM + assignments.sql();
            parameters = merged(selection, assignments);
        }
        return grouped(rows, seriesBy != null, "COUNT(DISTINCT g.row_value)", parameters);
    }

    public List<StageEntry> findStageEntries(CrmProfile profile, ReportRequest request, OffsetDateTime cutoff) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        Selection selection = selection(scope.get(), request, cutoff);
        return jdbcClient.sql("""
                        SELECT i.id AS interaction_id, i.created_at, t.id AS team_id, t.name AS team_name,
                               p.id AS program_id, p.name AS program_name,
                               e.stage_name_snapshot AS stage_name, e.occurred_at, st.stage_order,
                               CASE WHEN st.stage_order = fin.final_order THEN 1 ELSE 0 END AS final_stage,
                               i.workflow_template_id,
                               (SELECT rt.name FROM workflow_templates rt WHERE rt.id = i.workflow_template_id) AS template_name
                        """ + DURATION_FROM
                        + " JOIN (SELECT fs.interaction_id, MAX(fs.stage_order) AS final_order FROM interaction_stages fs"
                        + " JOIN interactions fi ON fi.id = fs.interaction_id"
                        + " WHERE fi.organization_id IN (SELECT id FROM organizations WHERE " + scope.get().condition() + ")"
                        + " GROUP BY fs.interaction_id) fin ON fin.interaction_id = i.id"
                        + selection.sql()
                        + " AND e.type IN ('CREATED', 'TRANSITIONED') AND i.created_at < :cutoff AND e.occurred_at < :cutoff"
                        + " ORDER BY i.id, e.occurred_at, e.version")
                .params(selection.parameters())
                .param("cutoff", cutoff)
                .query((resultSet, rowNumber) -> new StageEntry(
                        resultSet.getObject("interaction_id", UUID.class),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getString("team_name"),
                        resultSet.getObject("program_id", UUID.class),
                        resultSet.getString("program_name"),
                        resultSet.getString("stage_name"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getObject("stage_order", Integer.class),
                        resultSet.getInt("final_stage") == 1,
                        resultSet.getObject("workflow_template_id", UUID.class),
                        resultSet.getString("template_name")
                ))
                .list();
    }

    public List<ManagerWorkLine> findManagerWork(
            CrmProfile profile,
            ReportFilters filters,
            LocalDate from,
            LocalDate to,
            OffsetDateTime now,
            boolean managerAtPeriodEnd
    ) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        OffsetDateTime fromAt = from == null ? null : from.atStartOfDay(ReportRequest.ZONE).toOffsetDateTime();
        OffsetDateTime toAt = to == null ? null : to.plusDays(1).atStartOfDay(ReportRequest.ZONE).toOffsetDateTime();
        Map<String, Object> parameters = new HashMap<>(scope.get().parameters());
        if (fromAt != null) {
            parameters.put("fromAt", fromAt);
        }
        if (toAt != null) {
            parameters.put("toAt", toAt);
        }
        // Если период уже закончился, менеджер и этап определяются на его конец по истории (передачи вуза, события),
        // а не по текущему состоянию: иначе завершённый период пересчитывался бы по сегодняшнему владельцу вуза.
        boolean historical = managerAtPeriodEnd && toAt != null && toAt.isBefore(now);
        // Отбор по менеджеру идёт по тому же выражению, что и колонка «Менеджер»: в режиме «Сейчас» вуз без действующего
        // КАМ своей команды — «не назначен» и в отбор прежнего владельца не попадает; экран и файл совпадают строка в строку.
        String unassigned = OrganizationRepository.requiresAssignment("o");
        String managerId = historical
                ? MANAGER_AT.formatted("toAt")
                : "CASE WHEN " + unassigned + " THEN NULL ELSE o.owner_manager_id END";
        String startStage = fromAt == null ? "CAST(NULL AS VARCHAR(200))" : STAGE_AT.formatted("fromAt");
        String endStage = toAt != null && toAt.isBefore(now) ? STAGE_AT.formatted("toAt") : "s.name";
        // Область: действующие вузы пользователя (архивные не входят) и без служебной организации для оплат
        // физлиц: она не относится к работе менеджеров и в «Контроль» не попадает.
        List<String> conditions = new ArrayList<>(List.of(
                "i.organization_id IN (" + scope.get().currentOrganizations() + ")",
                "o.type <> 'OPEN_ENROLLMENT'"
        ));
        if (toAt != null) {
            conditions.add("i.created_at < :toAt");
        }
        // Без периода показываются идущие работы. С периодом — идущие и те, по которым в периоде были события: так
        // завершённая в периоде работа остаётся в отчёте за него. «За период» после его конца «идущие» — активные на
        // конец периода по истории статусов, а не сегодня: работа без событий в сентябре, завершённая в октябре, остаётся
        // в сентябре. Активная на начало и неактивная на конец меняла статус в периоде, её добавляют события.
        String active = historical ? WorkStatusAt.sql("toAt") + " = 'ACTIVE'" : "i.work_status = 'ACTIVE'";
        conditions.add(fromAt == null && toAt == null
                ? active
                : "(" + active + " OR EXISTS (SELECT 1 FROM interaction_events pe WHERE pe.interaction_id = i.id AND "
                        + inPeriod("pe.occurred_at", fromAt, toAt) + "))");
        organizationFilters(conditions, parameters, filters);
        dimension(conditions, parameters, "d.id", "directionIds", filters.directionIds(), filters.includeNoDirection());
        dimension(conditions, parameters, "i.program_id", "programIds", filters.programIds(), filters.includeNoProgram());
        dimension(conditions, parameters, "i.interaction_kind_id", "kindIds", filters.kindIds(), false);
        dimension(conditions, parameters, "(" + managerId + ")", "managerIds", filters.managerIds(), filters.includeNoManager());
        products(conditions, parameters, filters);
        if (!filters.stages().isEmpty()) {
            conditions.add("(" + endStage + ") IN (:stages)");
            parameters.put("stages", filters.stages());
        }
        // «Завершена в периоде»: последняя в периоде смена статуса перевела работу в COMPLETED. Вход в последний этап
        // маршрута завершением не считается, а завершение, отменённое повторным открытием в том же периоде, не засчитывается.
        String completedInPeriod = "(SELECT ke.work_status_after FROM interaction_events ke WHERE ke.interaction_id = i.id"
                + " AND ke.type = 'STATUS_CHANGED' AND " + inPeriod("ke.occurred_at", fromAt, toAt)
                + " ORDER BY ke.occurred_at DESC, ke.version DESC LIMIT 1) = 'COMPLETED'";
        String sql = "SELECT * FROM (SELECT i.id AS interaction_id, i.title AS interaction_title, o.id AS organization_id,"
                + " o.name AS organization_name, t.id AS team_id, t.name AS team_name,"
                + (historical
                        ? " " + managerId + " AS manager_id,"
                                + " (SELECT mp.display_name FROM crm_user_profiles mp WHERE mp.id = " + managerId + ") AS manager_name,"
                        : " " + managerId + " AS manager_id,"
                                + " CASE WHEN " + unassigned + " THEN NULL ELSE m.display_name END AS manager_name,")
                + " d.name AS direction_name, p.id AS program_id, p.name AS program_name, s.name AS stage_name,"
                + " i.interaction_kind_id AS kind_id, i.workflow_template_id AS route_id,"
                + " (SELECT COUNT(*) FROM interaction_stages ps WHERE ps.interaction_id = i.id AND ps.stage_order <= s.stage_order)"
                + " AS stage_position,"
                + " (SELECT COUNT(*) FROM interaction_stages ps WHERE ps.interaction_id = i.id) AS stage_total,"
                + " (SELECT ts.norm_days FROM workflow_template_stages ts WHERE ts.id = s.template_stage_id) AS stage_norm_days,"
                + " " + STAGE_ENTERED + " AS stage_entered_at, " + startStage + " AS start_stage, " + endStage + " AS end_stage,"
                + " i.next_action, i.next_action_at,\n" + MARKS
                + " (SELECT COUNT(*) FROM interaction_events te WHERE te.interaction_id = i.id AND te.type = 'TRANSITIONED'"
                + " AND " + inPeriod("te.occurred_at", fromAt, toAt) + ") AS transitions,"
                + " CASE WHEN " + inPeriod("i.created_at", fromAt, toAt) + " THEN 1 ELSE 0 END AS new_in_period,"
                // «Нет документов» — по текущему этапу и на сейчас, в том числе в режиме «За период».
                + " CASE WHEN " + InteractionRepository.missingDocumentsCondition("i") + " THEN 1 ELSE 0 END AS missing_documents,"
                + " CASE WHEN " + completedInPeriod + " THEN 1 ELSE 0 END AS completed_in_period "
                + MANAGER_WORK_FROM + " WHERE " + String.join(" AND ", conditions) + ") w"
                + (filters.change() == null ? "" : switch (filters.change()) {
                    case MOVED -> " WHERE w.transitions > 0";
                    case NEW -> " WHERE w.new_in_period = 1";
                    case COMPLETED -> " WHERE w.completed_in_period = 1";
                })
                + " ORDER BY w.organization_name, w.interaction_title, w.interaction_id";
        return jdbcClient.sql(sql)
                .params(parameters)
                .query((resultSet, rowNumber) -> {
                    OffsetDateTime stageEnteredAt = resultSet.getObject("stage_entered_at", OffsetDateTime.class);
                    return new ManagerWorkLine(
                            resultSet.getObject("interaction_id", UUID.class),
                            resultSet.getString("interaction_title"),
                            resultSet.getObject("organization_id", UUID.class),
                            resultSet.getString("organization_name"),
                            resultSet.getObject("team_id", UUID.class),
                            resultSet.getString("team_name"),
                            resultSet.getObject("manager_id", UUID.class),
                            resultSet.getString("manager_name"),
                            resultSet.getString("direction_name"),
                            resultSet.getString("program_name"),
                            resultSet.getString("stage_name"),
                            resultSet.getInt("stage_position"),
                            resultSet.getInt("stage_total"),
                            stageEnteredAt == null ? null : Math.max(0, Duration.between(stageEnteredAt, now).toDays()),
                            resultSet.getString("start_stage"),
                            resultSet.getString("end_stage"),
                            resultSet.getString("next_action"),
                            resultSet.getObject("next_action_at", OffsetDateTime.class),
                            InteractionIssueRepository.mapMarks(resultSet),
                            resultSet.getLong("transitions"),
                            resultSet.getInt("new_in_period") == 1,
                            resultSet.getInt("completed_in_period") == 1,
                            resultSet.getObject("program_id", UUID.class),
                            resultSet.getObject("kind_id", UUID.class),
                            resultSet.getObject("route_id", UUID.class),
                            resultSet.getInt("missing_documents") == 1,
                            resultSet.getObject("stage_norm_days", Integer.class)
                    );
                })
                .list();
    }

    // Действующие вузы области без идущего взаимодействия — для группы «Без работы» и чисел «Контроля», которые должны
    // совпасть с разделом «Вузы». Ответственный — по тому же правилу, что в строках «Контроля»: без действующего КАМ
    // своей команды вуз считается требующим назначения.
    public List<ManagerWorkOrganization> findOrganizationsWithoutActiveWork(CrmProfile profile) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        String unassigned = OrganizationRepository.requiresAssignment("o");
        return jdbcClient.sql("SELECT o.id, o.name, t.id AS team_id, t.name AS team_name,"
                        + " CASE WHEN " + unassigned + " THEN NULL ELSE o.owner_manager_id END AS manager_id,"
                        + " CASE WHEN " + unassigned + " THEN NULL ELSE m.display_name END AS manager_name,"
                        + " " + LAST_STAFF_ACTION + " AS last_action_at, o.type"
                        + " FROM organizations o JOIN teams t ON t.id = o.team_id"
                        + " LEFT JOIN crm_user_profiles m ON m.id = o.owner_manager_id"
                        + " WHERE o.id IN (" + scope.get().currentOrganizations() + ") AND o.type <> 'OPEN_ENROLLMENT'"
                        + " AND NOT EXISTS (SELECT 1 FROM interactions i WHERE i.organization_id = o.id AND i.work_status = 'ACTIVE')"
                        + " ORDER BY o.name, o.id")
                .params(scope.get().parameters())
                .query((resultSet, rowNumber) -> new ManagerWorkOrganization(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getString("team_name"),
                        resultSet.getObject("manager_id", UUID.class),
                        resultSet.getString("manager_name"),
                        resultSet.getObject("last_action_at", OffsetDateTime.class),
                        OrganizationType.valueOf(resultSet.getString("type"))
                ))
                .list();
    }

    public Map<UUID, List<String>> findManagerWorkProducts(List<UUID> interactionIds) {
        Map<UUID, List<String>> products = new HashMap<>();
        findManagerWorkProductRefs(interactionIds).forEach((id, refs) -> products.put(id, refs.stream().map(ProductRef::name).toList()));
        return products;
    }

    public Map<UUID, List<ProductRef>> findManagerWorkProductRefs(List<UUID> interactionIds) {
        Map<UUID, List<ProductRef>> products = new HashMap<>();
        for (int start = 0; start < interactionIds.size(); start += PRODUCT_BATCH_SIZE) {
            jdbcClient.sql("""
                    SELECT DISTINCT pa.interaction_id, pr.id, pr.name
                    FROM product_agreements pa
                    JOIN products pr ON pr.id = pa.product_id
                    WHERE pa.interaction_id IN (:interactionIds) AND pa.archived_at IS NULL
                    ORDER BY pr.name, pr.id
                    """)
                    .param("interactionIds", interactionIds.subList(start, Math.min(start + PRODUCT_BATCH_SIZE, interactionIds.size())))
                    .query((ResultSet resultSet) -> {
                        products.computeIfAbsent(resultSet.getObject("interaction_id", UUID.class), id -> new ArrayList<>())
                                .add(new ProductRef(resultSet.getObject("id", UUID.class), resultSet.getString("name")));
                    });
        }
        return products;
    }

    public record ProductRef(UUID id, String name) {
    }

    public Map<UUID, List<String>> findManagerWorkComments(List<UUID> interactionIds, LocalDate from, LocalDate to) {
        OffsetDateTime fromAt = from == null ? null : from.atStartOfDay(ReportRequest.ZONE).toOffsetDateTime();
        OffsetDateTime toAt = to == null ? null : to.plusDays(1).atStartOfDay(ReportRequest.ZONE).toOffsetDateTime();
        Map<String, Object> parameters = new HashMap<>();
        if (fromAt != null) {
            parameters.put("fromAt", fromAt);
        }
        if (toAt != null) {
            parameters.put("toAt", toAt);
        }
        Map<UUID, List<String>> comments = new HashMap<>();
        for (int start = 0; start < interactionIds.size(); start += PRODUCT_BATCH_SIZE) {
            // В отчёт идут только комментарии людей (EMPLOYEE_EVENT), без контактов, чтобы не выгружать ФИО, почту и телефоны.
            jdbcClient.sql("SELECT le.interaction_id, le.occurred_at, NULLIF(le.comment, '') AS event_comment"
                            + " FROM interaction_events le WHERE le.interaction_id IN (:interactionIds)"
                            + " AND le.type IN ('COMMENTED', 'TRANSITIONED')"
                            + EMPLOYEE_EVENT
                            + " AND " + inPeriod("le.occurred_at", fromAt, toAt)
                            + " ORDER BY le.occurred_at, le.version, le.id")
                    .params(parameters)
                    .param("interactionIds", interactionIds.subList(start, Math.min(start + PRODUCT_BATCH_SIZE, interactionIds.size())))
                    .query((ResultSet resultSet) -> {
                        String text = resultSet.getString("event_comment");
                        if (text != null) {
                            comments.computeIfAbsent(resultSet.getObject("interaction_id", UUID.class), id -> new ArrayList<>())
                                    .add(DATE.format(resultSet.getObject("occurred_at", OffsetDateTime.class)
                                            .atZoneSameInstant(ReportRequest.ZONE)) + ": " + text);
                        }
                    });
        }
        return comments;
    }

    private static String inPeriod(String column, OffsetDateTime fromAt, OffsetDateTime toAt) {
        List<String> bounds = new ArrayList<>();
        if (fromAt != null) {
            bounds.add(column + " >= :fromAt");
        }
        if (toAt != null) {
            bounds.add(column + " < :toAt");
        }
        return bounds.isEmpty() ? "TRUE" : "(" + String.join(" AND ", bounds) + ")";
    }

    // Все строки выборки сразу: место по индексу зависит от всех программ выборки, страница режется уже после расчёта.
    // freshAfter — граница свежести наблюдения Moodle, как у сигналов LMS: активность по более старому не считается.
    public List<ReportRow> findDemandRows(CrmProfile profile, ReportRequest request, OffsetDateTime freshAfter) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        Selection selection = demandUnion(scope.get(), request);
        String metric = switch (request.sortBy()) {
            case PAID_ORDERS -> "paid_orders";
            case PAID_STREAMS -> "paid_streams";
            case PARTICIPANTS -> "participants";
            case PARALLEL_RUNS -> "parallel_runs";
            case LEARNERS_COMPLETED -> "completed";
            default -> "applications";
        };
        List<ReportRow> rows = jdbcClient.sql("""
                        SELECT d.id AS direction_id, d.name AS direction_name, p.id AS program_id, p.name AS program_name,
                               CAST(SUM(x.applications) AS BIGINT) AS applications,
                               CAST(SUM(x.participants) AS BIGINT) AS participants,
                               CAST(SUM(x.completed) AS BIGINT) AS completed,
                               CAST(SUM(x.completed_base) AS BIGINT) AS completed_base,
                               CAST(SUM(x.runs_without_data) AS BIGINT) AS runs_without_data,
                               CAST(SUM(x.parallel_runs) AS BIGINT) AS parallel_runs,
                               CAST(CASE WHEN COUNT(x.paid_order) = 0 THEN NULL ELSE COUNT(DISTINCT x.paid_order) END AS BIGINT)
                                   AS paid_orders,
                               CAST(CASE WHEN COUNT(x.paid_stream) = 0 THEN NULL ELSE COUNT(DISTINCT x.paid_stream) END AS BIGINT)
                                   AS paid_streams,
                               CAST(SUM(x.active_7d) AS BIGINT) AS active_7d,
                               CAST(SUM(x.activity_base) AS BIGINT) AS activity_base,
                               CAST(SUM(x.stale_runs) AS BIGINT) AS stale_runs
                        FROM (""" + selection.sql() + """
                        ) x
                        LEFT JOIN programs p ON p.id = x.program_id
                        LEFT JOIN directions d ON d.id = p.direction_id
                        GROUP BY d.id, d.name, p.id, p.name
                        ORDER BY %s DESC NULLS LAST, p.name NULLS LAST, d.name, p.id
                        """.formatted(metric))
                .params(selection.parameters())
                .param("freshAfter", freshAfter)
                .query(this::mapDemandRow)
                .list();
        Map<UUID, Long> interactions = programInteractions(scope.get(), request);
        return withMarket(rows.stream()
                .map(row -> row.programId() == null ? row
                        : row.withDemand(row.demand().withInteractions(interactions.getOrDefault(row.programId(), 0L))))
                .toList(), request);
    }

    // Спрос вузов (М17): взаимодействия по программе, созданные в периоде, в области пользователя. Отбор по виду
    // взаимодействия действует только здесь: у заявок сайта и потоков Moodle вида нет. Данные CRM полные, поэтому
    // программа без взаимодействий — ноль, а не «нет данных».
    private Map<UUID, Long> programInteractions(VisibilityScope scope, ReportRequest request) {
        List<String> conditions = new ArrayList<>(List.of(
                "i.organization_id IN (" + scope.currentOrganizations() + ")",
                "o.type <> 'OPEN_ENROLLMENT'",
                "i.program_id IS NOT NULL"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.addAll(bounds("i.created_at", request, parameters));
        ReportFilters filters = request.filters();
        organizationFilters(conditions, parameters, filters);
        dimension(conditions, parameters, "d.id", "directionIds", filters.directionIds(), filters.includeNoDirection());
        dimension(conditions, parameters, "i.program_id", "programIds", filters.programIds(), false);
        dimension(conditions, parameters, "o.owner_manager_id", "managerIds", filters.managerIds(), filters.includeNoManager());
        dimension(conditions, parameters, "i.interaction_kind_id", "kindIds", filters.kindIds(), false);
        Map<UUID, Long> counts = new HashMap<>();
        jdbcClient.sql("SELECT i.program_id, CAST(COUNT(*) AS BIGINT) AS interactions FROM interactions i"
                        + " JOIN organizations o ON o.id = i.organization_id"
                        + " LEFT JOIN programs p ON p.id = i.program_id LEFT JOIN directions d ON d.id = p.direction_id"
                        + " WHERE " + String.join(" AND ", conditions) + " GROUP BY i.program_id")
                .params(parameters)
                .query(resultSet -> {
                    counts.put(resultSet.getObject("program_id", UUID.class), resultSet.getLong("interactions"));
                });
        return counts;
    }

    private List<GroupCount> grouped(String rows, boolean series, String aggregate, Map<String, Object> parameters) {
        String columns = series ? "g.group_key, g.group_label, g.series_key, g.series_label" : "g.group_key, g.group_label";
        return jdbcClient.sql("SELECT " + columns + ", " + aggregate + " AS row_count FROM (" + rows + ") g GROUP BY " + columns)
                .params(parameters)
                .query((resultSet, rowNumber) -> new GroupCount(
                        resultSet.getString("group_key"),
                        resultSet.getString("group_label"),
                        series ? resultSet.getString("series_key") : null,
                        series ? resultSet.getString("series_label") : null,
                        resultSet.getLong("row_count")
                ))
                .list();
    }

    private static String groupSelect(GroupColumns group, GroupColumns series, String rowValue) {
        return "SELECT " + text(group.key()) + " AS group_key, " + text(group.label()) + " AS group_label, "
                + (series == null ? "" : text(series.key()) + " AS series_key, " + text(series.label()) + " AS series_label, ")
                + rowValue + " AS row_value ";
    }

    private static String text(String expression) {
        return "CAST(" + expression + " AS VARCHAR(300))";
    }

    private static GroupColumns groupColumns(ReportKind kind, StatisticsGroupBy groupBy) {
        return switch (groupBy) {
            case STAGE -> new GroupColumns(stageColumn(kind), stageColumn(kind));
            case ORGANIZATION -> new GroupColumns("o.id", "o.name");
            case DIRECTION -> new GroupColumns("d.id", "d.name");
            case PROGRAM -> new GroupColumns("p.id", "p.name");
            case PRODUCT -> new GroupColumns("pr.id", "pr.name");
            case MANAGER -> new GroupColumns(managerColumn(kind), "m.display_name");
            case MONTH -> month(kind == ReportKind.EVENTS ? "e.occurred_at" : "i.created_at");
            case KIND -> new GroupColumns("i.interaction_kind_id",
                    "(SELECT gk.name FROM interaction_kinds gk WHERE gk.id = i.interaction_kind_id)");
            case MILESTONE -> new GroupColumns("mr.lms_role", "CASE mr.lms_role"
                    + Arrays.stream(StageLmsRole.values()).map(role -> " WHEN '" + role.name() + "' THEN '" + role.title() + "'")
                            .collect(Collectors.joining()) + " END");
        };
    }

    // Веха (М08 в docs/metrics.md) — смысловая отметка, одинаковая для разных маршрутов: взаимодействие вошло в этап с
    // ролью LMS (событие создания или перехода в этап) или этот этап отмечен выполненным. Одно взаимодействие может
    // достичь нескольких вех, поэтому строки соединения не взаимоисключающие (как у ИТ-продуктов); без вех — NULL,
    // то есть «Не указано». Для состояния на дату учитываются только события и отметки до границы.
    private static String milestoneJoin(boolean snapshot) {
        String eventCut = snapshot ? " AND me.occurred_at < :asOfAt" : "";
        String markCut = snapshot ? " AND mce.occurred_at < :asOfAt" : "";
        return " LEFT JOIN (SELECT DISTINCT ms.interaction_id, ms.lms_role FROM interaction_stages ms"
                + " WHERE ms.lms_role IS NOT NULL AND (EXISTS (SELECT 1 FROM interaction_events me"
                + " WHERE me.interaction_id = ms.interaction_id AND me.stage_id = ms.id AND me.type IN ('CREATED', 'TRANSITIONED')"
                + eventCut + ") OR EXISTS (SELECT 1 FROM interaction_stage_completions mc"
                + " JOIN interaction_events mce ON mce.id = mc.event_id"
                + " WHERE mc.interaction_id = ms.interaction_id AND mc.stage_id = ms.id" + markCut + "))) mr"
                + " ON mr.interaction_id = i.id";
    }

    private static GroupColumns routeStageColumns(ReportKind kind) {
        String stage = stageColumn(kind);
        return new GroupColumns(
                "CASE WHEN " + stage + " IS NULL THEN NULL ELSE CONCAT(COALESCE(CAST(i.workflow_template_id AS VARCHAR(36)), '"
                        + StageRoute.NO_TEMPLATE + "'), '|', " + stage + ") END",
                "CASE WHEN " + stage + " IS NULL THEN NULL ELSE CONCAT(COALESCE((SELECT rt.name FROM workflow_templates rt"
                        + " WHERE rt.id = i.workflow_template_id), 'Без шаблона'), ': ', " + stage + ") END"
        );
    }

    // Маршруты работ выборки для диаграммы по этапам. Порядок этапов — по шаблону самих работ: сначала этапы шаблона
    // (в том числе без работ, чтобы звенья маршрута были видны нулём), затем этапы работ вне шаблона по их наименьшему
    // номеру в карточках. Маршруты идут по названию шаблона.
    public List<StageRoute> findStageRoutes(CrmProfile profile, ReportRequest request, OffsetDateTime now) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty() || request.kind() == ReportKind.DEMAND) {
            return List.of();
        }
        Selection selection = selection(scope.get(), request, now);
        String from = switch (request.kind()) {
            case EVENTS -> EVENTS_FROM;
            case SNAPSHOT -> SNAPSHOT_FROM;
            default -> PORTFOLIO_FROM;
        };
        List<UUID> templateIds = new ArrayList<>();
        jdbcClient.sql("SELECT DISTINCT i.workflow_template_id " + from + selection.sql())
                .params(selection.parameters())
                .query((ResultSet resultSet) -> {
                    templateIds.add(resultSet.getObject(1, UUID.class));
                });
        return stageRoutes(scope.get(), templateIds);
    }

    // Маршруты строк «Контроля» для матрицы «программа × этап»: тот же порядок этапов, что у диаграммы портфеля.
    public List<StageRoute> findStageRoutes(CrmProfile profile, Collection<UUID> templateIds) {
        return organizationRepository.visibilityScope(profile)
                .map(scope -> stageRoutes(scope, templateIds))
                .orElse(List.of());
    }

    private List<StageRoute> stageRoutes(VisibilityScope scope, Collection<UUID> templateIds) {
        List<StageRoute> routes = new ArrayList<>();
        for (UUID templateId : templateIds) {
            String templateName = templateId == null ? null : jdbcClient.sql("SELECT name FROM workflow_templates WHERE id = :id")
                    .param("id", templateId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            // Работы без связи с шаблоном (до версий маршрута) упорядочиваются по общему шаблону по умолчанию, как раньше.
            List<String> stages = new ArrayList<>(templateId == null
                    ? jdbcClient.sql("""
                            SELECT ts.name FROM workflow_template_stages ts
                            JOIN workflow_templates t ON t.id = ts.template_id AND t.default_template = TRUE
                            ORDER BY ts.stage_order, ts.name
                            """).query(String.class).list()
                    : jdbcClient.sql(
                                    "SELECT name FROM workflow_template_stages WHERE template_id = :templateId ORDER BY stage_order, name")
                            .param("templateId", templateId)
                            .query(String.class)
                            .list());

            String sameRoute = templateId == null ? "i.workflow_template_id IS NULL" : "i.workflow_template_id = :templateId";
            JdbcClient.StatementSpec own = jdbcClient.sql("""
                    SELECT st.name, st.template_stage_id,
                           (SELECT prev.name FROM interaction_stages prev
                            WHERE prev.interaction_id = st.interaction_id AND prev.stage_order < st.stage_order
                            ORDER BY prev.stage_order DESC LIMIT 1) AS previous_name
                    FROM interaction_stages st
                    JOIN interactions i ON i.id = st.interaction_id
                    WHERE i.organization_id IN (%s) AND %s
                    ORDER BY st.stage_order, st.name
                    """.formatted(scope.currentOrganizations(), sameRoute))
                    .params(scope.parameters());
            if (templateId != null) {
                own = own.param("templateId", templateId);
            }
            // Этап, который КАМ добавил в маршрут своей карточки (без связи с этапом шаблона), встаёт после этапа,
            // за которым он идёт в карточке: это звено маршрута, а не пережиток. Этапы прежних версий шаблона идут
            // после текущих и помечаются: иначе матрица показывала их номером после финального, будто маршрут
            // продолжается (Н-РТ-3).
            List<String> card = new ArrayList<>();
            List<String> former = new ArrayList<>();
            List<String> trailingCard = new ArrayList<>();
            for (Map<String, Object> row : own.query().listOfRows()) {
                String name = (String) row.get("name");
                if (stages.contains(name) || former.contains(name) || trailingCard.contains(name)) {
                    continue;
                }
                if (row.get("template_stage_id") != null) {
                    former.add(name);
                    continue;
                }
                int previous = stages.indexOf((String) row.get("previous_name"));
                if (previous < 0) {
                    trailingCard.add(name);
                } else {
                    stages.add(previous + 1, name);
                }
                card.add(name);
            }
            stages.addAll(trailingCard);
            stages.addAll(former);
            routes.add(new StageRoute(templateId, templateName, List.copyOf(stages), List.copyOf(former), List.copyOf(card)));
        }
        routes.sort(Comparator.comparing(
                (StageRoute route) -> route.templateName() == null ? "" : route.templateName(), String.CASE_INSENSITIVE_ORDER));
        return routes;
    }

    private static GroupColumns assignmentGroupColumns(StatisticsGroupBy groupBy) {

        return switch (groupBy) {
            case ORGANIZATION -> new GroupColumns("o.id", "o.name");
            case MANAGER -> new GroupColumns("ae.owner_manager_id", "m.display_name");
            case MONTH -> month("ae.occurred_at");
            case STAGE, DIRECTION, PROGRAM, PRODUCT, KIND, MILESTONE -> new GroupColumns("NULL", "NULL");
        };
    }

    private static GroupColumns demandGroupColumns(StatisticsGroupBy groupBy) {
        return switch (groupBy) {
            case ORGANIZATION -> new GroupColumns("o.id", "o.name");
            case DIRECTION -> new GroupColumns("d.id", "d.name");
            case PROGRAM -> new GroupColumns("p.id", "p.name");
            case MANAGER -> new GroupColumns("o.owner_manager_id", "m.display_name");
            case MONTH -> month("r.submitted_at");
            case STAGE, PRODUCT, KIND, MILESTONE -> throw new IllegalArgumentException("Unsupported demand grouping " + groupBy);
        };
    }

    private static GroupColumns month(String column) {
        String month = "CAST(EXTRACT(YEAR FROM %1$s AT TIME ZONE '%2$s') * 100 + EXTRACT(MONTH FROM %1$s AT TIME ZONE '%2$s')"
                .formatted(column, ReportRequest.ZONE.getId()) + " AS INTEGER)";
        return new GroupColumns(month, month);
    }

    private static String stageColumn(ReportKind kind) {
        return switch (kind) {
            case EVENTS -> "e.stage_name_snapshot";
            case SNAPSHOT -> "sn.stage_name";
            default -> "s.name";
        };
    }

    private static String managerColumn(ReportKind kind) {
        return switch (kind) {
            case EVENTS -> "e.owner_manager_id_snapshot";
            case SNAPSHOT -> "sn.manager_id";
            default -> "o.owner_manager_id";
        };
    }

    public List<ReportManagerOption> findManagerOptions(CrmProfile profile) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        if (profile.role() == UserRole.USER) {
            return jdbcClient.sql("SELECT id, display_name, active FROM crm_user_profiles WHERE id = :profileId")
                    .param("profileId", profile.id())
                    .query(this::mapManager)
                    .list();
        }
        return jdbcClient.sql("""
                SELECT id, display_name, active
                FROM crm_user_profiles
                WHERE (team_id = :managerTeamId AND role = 'USER')
                   OR id IN (SELECT owner_manager_id FROM organizations WHERE %s)
                ORDER BY display_name, id
                """.formatted(scope.get().condition()))
                .params(scope.get().parameters())
                .param("managerTeamId", profile.teamId())
                .query(this::mapManager)
                .list();
    }

    public List<String> findStageNames(CrmProfile profile) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        // Порядок — по маршруту шаблона по умолчанию: правка этапов в одном взаимодействии (этап вставлен вторым)
        // не должна сдвигать этап в общем списке. Этапы вне шаблона идут следом по наименьшему номеру.
        return jdbcClient.sql("""
                SELECT st.name
                FROM interaction_stages st
                JOIN interactions i ON i.id = st.interaction_id
                LEFT JOIN (
                    SELECT ts.name AS route_name, MIN(ts.stage_order) AS route_order
                    FROM workflow_template_stages ts
                    JOIN workflow_templates t ON t.id = ts.template_id AND t.default_template = TRUE
                    GROUP BY ts.name
                ) route ON route.route_name = st.name
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                GROUP BY st.name
                ORDER BY CASE WHEN MIN(route.route_order) IS NULL THEN 1 ELSE 0 END, MIN(route.route_order), MIN(st.stage_order), st.name
                """.formatted(scope.get().condition()))
                .params(scope.get().parameters())
                .query(String.class)
                .list();
    }

    public Map<UUID, String> findOrganizationNames(CrmProfile profile, List<UUID> organizationIds) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty() || organizationIds.isEmpty()) {
            return Map.of();
        }
        return names(jdbcClient.sql("""
                SELECT id, name
                FROM organizations
                WHERE id IN (:organizationIds) AND %s
                """.formatted(scope.get().condition()))
                .params(scope.get().parameters())
                .param("organizationIds", organizationIds));
    }

    public Map<UUID, String> findCatalogNames(CatalogTable table, List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return names(jdbcClient.sql("SELECT id, name FROM " + table.tableName() + " WHERE id IN (:ids)")
                .param("ids", ids));
    }

    private Map<UUID, String> names(JdbcClient.StatementSpec statement) {
        Map<UUID, String> names = new HashMap<>();
        statement.query((ResultSet resultSet) -> {
            names.put(resultSet.getObject("id", UUID.class), resultSet.getString("name"));
        });
        return names;
    }

    private Selection rowSelection(CrmProfile profile, VisibilityScope scope, ReportRequest request, OffsetDateTime now) {
        Selection selection = selection(scope, request, now);
        return switch (request.kind()) {
            case EVENTS -> {
                Selection assignments = assignmentSelection(profile, scope, request);
                yield new Selection("SELECT * FROM (" + EVENTS_SELECT + EVENTS_FROM + selection.sql() + " UNION ALL "
                        + ASSIGNMENT_SELECT + ASSIGNMENT_FROM + assignments.sql() + ") x ORDER BY x.event_at, x.event_id",
                        merged(selection, assignments));
            }
            case SNAPSHOT -> new Selection(SNAPSHOT_SELECT + SNAPSHOT_FROM + selection.sql()
                    + " ORDER BY o.name, i.created_at, i.id", selection.parameters());
            case PORTFOLIO -> new Selection(PORTFOLIO_SELECT + PORTFOLIO_FROM + selection.sql()
                    + " ORDER BY o.name, i.created_at, i.id", selection.parameters());
            case DEMAND, DURATION, AGREEMENTS, MANAGER_WORK -> throw new IllegalArgumentException("Rows of " + request.kind() + " are not selected here");
        };
    }

    private Selection selection(VisibilityScope scope, ReportRequest request, OffsetDateTime now) {
        ReportKind kind = request.kind();
        ReportFilters filters = request.filters();
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.add("i.organization_id IN (" + scope.currentOrganizations() + ")");
        period(conditions, parameters, request);
        organizationFilters(conditions, parameters, filters);
        dimension(conditions, parameters, "d.id", "directionIds", filters.directionIds(), filters.includeNoDirection());
        dimension(conditions, parameters, "i.program_id", "programIds", filters.programIds(), filters.includeNoProgram());
        dimension(conditions, parameters, "i.interaction_kind_id", "kindIds", filters.kindIds(), false);
        dimension(conditions, parameters, managerColumn(kind), "managerIds", filters.managerIds(), filters.includeNoManager());
        products(conditions, parameters, filters);
        agreements(conditions, parameters, filters);
        if (!filters.stages().isEmpty() && kind != ReportKind.DURATION) {
            conditions.add(stageColumn(kind) + " IN (:stages)");
            parameters.put("stages", filters.stages());
        }
        if (!filters.workStatuses().isEmpty()) {
            // В «Состоянии на дату» статус — на эту дату по истории, как этап и ответственный.
            conditions.add((kind == ReportKind.SNAPSHOT ? WorkStatusAt.sql("asOfAt") : "i.work_status") + " IN (:workStatuses)");
            parameters.put("workStatuses", filters.workStatuses().stream().map(Enum::name).toList());
        }
        filters.flags().forEach(flag -> conditions.add(flag.condition()));
        if (kind == ReportKind.EVENTS && !filters.eventTypes().isEmpty()) {
            List<String> types = filters.eventTypes().stream()
                    .filter(type -> type != ReportEventType.ASSIGNMENT)
                    .map(Enum::name)
                    .toList();
            if (types.isEmpty()) {
                conditions.add(NO_ROWS);
            } else {
                conditions.add("e.type IN (:eventTypes)");
                parameters.put("eventTypes", types);
            }
        }
        if (filters.minDaysOnStage() != null) {
            conditions.add(STAGE_ENTERED + " <= :stageEnteredBefore");
            parameters.put("stageEnteredBefore", now.minusDays(filters.minDaysOnStage()));
        }
        if (kind == ReportKind.SNAPSHOT) {
            parameters.put("asOfAt", request.asOfAt());
        }
        return new Selection(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    private Selection assignmentSelection(CrmProfile profile, VisibilityScope scope, ReportRequest request) {
        ReportFilters filters = request.filters();
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.add("ae.organization_id IN (" + scope.currentOrganizations() + ")");
        conditions.addAll(bounds("ae.occurred_at", request, parameters));
        organizationFilters(conditions, parameters, filters);
        dimension(conditions, parameters, "ae.owner_manager_id", "managerIds", filters.managerIds(), filters.includeNoManager());
        boolean excluded = profile.role() != UserRole.LEADER && profile.role() != UserRole.MANAGEMENT
                || !filters.eventTypes().isEmpty() && !filters.eventTypes().contains(ReportEventType.ASSIGNMENT)
                || onlySpecified(filters.directionIds(), filters.includeNoDirection())
                || onlySpecified(filters.programIds(), filters.includeNoProgram())
                || onlySpecified(filters.productIds(), filters.includeNoProduct())
                || !filters.kindIds().isEmpty()
                || !filters.stages().isEmpty()
                || !filters.workStatuses().isEmpty()
                || !filters.flags().isEmpty()
                || !filters.agreement().unrestricted();
        if (excluded) {
            conditions.add(NO_ROWS);
        }
        return new Selection(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    private static boolean onlySpecified(List<UUID> ids, boolean includeUnspecified) {
        return !ids.isEmpty() && !includeUnspecified;
    }

    private static Map<String, Object> merged(Selection first, Selection second) {
        Map<String, Object> parameters = new HashMap<>(first.parameters());
        parameters.putAll(second.parameters());
        return parameters;
    }

    private Selection demandUnion(VisibilityScope scope, ReportRequest request) {
        Selection applications = demandSelection(scope, request);
        Selection learning = learningSelection(scope, request);
        Selection paidOrders = paidOrderSelection(scope, request);
        Map<String, Object> parameters = merged(applications, learning);
        parameters.putAll(paidOrders.parameters());
        parameters.put("runsAsOf", request.runsAsOf());
        return new Selection(
                "SELECT r.program_id, CAST(r.applications_count AS BIGINT) AS applications, CAST(NULL AS BIGINT) AS participants,"
                        + " CAST(NULL AS BIGINT) AS completed, CAST(NULL AS BIGINT) AS completed_base,"
                        + " CAST(NULL AS BIGINT) AS runs_without_data, CAST(NULL AS BIGINT) AS parallel_runs,"
                        + " CAST(NULL AS VARCHAR(200)) AS paid_order, CAST(NULL AS VARCHAR(64)) AS paid_stream,"
                        + " CAST(NULL AS BIGINT) AS active_7d, CAST(NULL AS BIGINT) AS activity_base,"
                        + " CAST(NULL AS BIGINT) AS stale_runs " + DEMAND_FROM
                        + applications.sql()
                        // Знаменатель доли — C + N, как в «Динамике обучения»: участия без статуса в него не входят.
                        // Поток без наблюдения на дату считается отдельно, а не нулём обучающихся.
                        + " UNION ALL SELECT s.program_id, CAST(NULL AS BIGINT), CAST(f.participants_count AS BIGINT),"
                        + " CAST(f.completed_count AS BIGINT),"
                        + " CAST(f.completed_count + f.not_completed_count AS BIGINT),"
                        + " CAST(CASE WHEN f.mapping_id IS NULL THEN 1 ELSE 0 END AS BIGINT),"
                        + " CAST(CASE WHEN sm.run_starts_on <= :runsAsOf AND :runsAsOf < sm.run_ends_on THEN 1 ELSE 0 END"
                        + " AS BIGINT), CAST(NULL AS VARCHAR(200)), CAST(NULL AS VARCHAR(64)),"
                        // Активность меняется каждый день: по наблюдению старше порога устаревания она не берётся,
                        // как в сигналах LMS; такой поток назван в покрытии индекса.
                        + " CAST(CASE WHEN f.confirmed_at >= :freshAfter AND f.active_7d_count IS NOT NULL"
                        + " THEN f.active_7d_count END AS BIGINT),"
                        + " CAST(CASE WHEN f.confirmed_at >= :freshAfter AND f.active_7d_count IS NOT NULL"
                        + " THEN f.participants_count END AS BIGINT),"
                        + " CAST(CASE WHEN f.mapping_id IS NOT NULL AND f.confirmed_at < :freshAfter THEN 1 ELSE 0 END AS BIGINT) "
                        + LEARNING_FROM + learning.sql()
                        + " UNION ALL SELECT r.program_id, CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), CAST(NULL AS BIGINT),"
                        + " CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), CAST(r.external_id AS VARCHAR(200)),"
                        + " CAST(r.payload_hash AS VARCHAR(64)), CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), CAST(NULL AS BIGINT) "
                        + DEMAND_FROM + paidOrders.sql(),
                parameters
        );
    }

    private Selection paidOrderSelection(VisibilityScope scope, ReportRequest request) {
        List<String> conditions = new ArrayList<>(List.of(
                "r.source = 'WEBSITE'",
                "r.record_type = 'paid_order'",
                "r.status = 'APPLIED'",
                "(o.type = 'OPEN_ENROLLMENT' OR r.organization_id IN (" + scope.currentOrganizations() + "))"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        demandFilters(conditions, parameters, request.filters(), "r.program_id");
        return new Selection(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    private Selection learningSelection(VisibilityScope scope, ReportRequest request) {
        List<String> conditions = new ArrayList<>(List.of(
                "s.organization_id IN (" + scope.currentOrganizations() + ")"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        parameters.put("learningAt", request.learningAt());
        if (request.from() != null) {
            conditions.add("sm.run_ends_on > :learningFrom");
            parameters.put("learningFrom", request.from());
        }
        if (request.to() != null) {
            conditions.add("sm.run_starts_on <= :learningTo");
            parameters.put("learningTo", request.to());
        }
        demandFilters(conditions, parameters, request.filters(), "s.program_id");
        return new Selection(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    private Selection demandSelection(VisibilityScope scope, ReportRequest request) {
        List<String> conditions = new ArrayList<>(List.of(
                "r.source = 'WEBSITE'",
                "r.record_type = 'learning_application'",
                "r.status = 'APPLIED'",
                "r.organization_id IN (" + scope.currentOrganizations() + ")"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        period(conditions, parameters, request);
        demandFilters(conditions, parameters, request.filters(), "r.program_id");
        return new Selection(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    private void demandFilters(List<String> conditions, Map<String, Object> parameters, ReportFilters filters, String programColumn) {
        organizationFilters(conditions, parameters, filters);
        dimension(conditions, parameters, "d.id", "directionIds", filters.directionIds(), filters.includeNoDirection());
        dimension(conditions, parameters, programColumn, "programIds", filters.programIds(), filters.includeNoProgram());
        dimension(conditions, parameters, "o.owner_manager_id", "managerIds", filters.managerIds(), filters.includeNoManager());
    }

    private void organizationFilters(List<String> conditions, Map<String, Object> parameters, ReportFilters filters) {
        if (!filters.organizationIds().isEmpty()) {
            conditions.add("o.id IN (:organizationIds)");
            parameters.put("organizationIds", filters.organizationIds());
        }
        if (filters.organizationType() != null) {
            conditions.add("o.type = :organizationType");
            parameters.put("organizationType", filters.organizationType().name());
        }
    }

    private void period(List<String> conditions, Map<String, Object> parameters, ReportRequest request) {
        switch (request.kind()) {
            case EVENTS -> conditions.addAll(bounds("e.occurred_at", request, parameters));
            case DEMAND -> conditions.addAll(bounds("r.submitted_at", request, parameters));
            case PORTFOLIO -> portfolioPeriod(conditions, parameters, request);
            case SNAPSHOT, DURATION, MANAGER_WORK -> {
            }
        }
    }

    // Три основы периода для портфеля: созданные в периоде; имевшие события в периоде; идущие к концу периода
    // (созданные до его конца; начало периода их не ограничивает, потому что идущая работа остаётся в портфеле).
    private void portfolioPeriod(List<String> conditions, Map<String, Object> parameters, ReportRequest request) {
        switch (request.periodBasis()) {
            case CREATED -> conditions.addAll(bounds("i.created_at", request, parameters));
            case ACTIVITY -> {
                List<String> bounds = bounds("pe.occurred_at", request, parameters);
                if (!bounds.isEmpty()) {
                    conditions.add("EXISTS (SELECT 1 FROM interaction_events pe WHERE pe.interaction_id = i.id AND "
                            + String.join(" AND ", bounds) + ")");
                }
            }
            case ACTIVE -> {
                if (request.toAt() != null) {
                    conditions.add("i.created_at < :toAt");
                    parameters.put("toAt", request.toAt());
                }
                // Не завершённые к началу периода по истории статусов, а также возобновлённые внутри него: завершённая
                // до периода работа в «Активные в периоде» не входит, сколько бы событий у неё потом ни было.
                if (request.fromAt() != null) {
                    parameters.put("fromAt", request.fromAt());
                    conditions.add("(COALESCE(" + WorkStatusAt.sql("fromAt") + ", 'ACTIVE') <> 'COMPLETED'"
                            + " OR EXISTS (SELECT 1 FROM interaction_events re WHERE re.interaction_id = i.id"
                            + " AND re.type = 'STATUS_CHANGED' AND re.work_status_after = 'ACTIVE' AND re.occurred_at >= :fromAt"
                            + (request.toAt() == null ? "" : " AND re.occurred_at < :toAt") + "))");
                }
            }
        }
    }

    private static List<String> bounds(String column, ReportRequest request, Map<String, Object> parameters) {
        List<String> bounds = new ArrayList<>();
        if (request.fromAt() != null) {
            bounds.add(column + " >= :fromAt");
            parameters.put("fromAt", request.fromAt());
        }
        if (request.toAt() != null) {
            bounds.add(column + " < :toAt");
            parameters.put("toAt", request.toAt());
        }
        return bounds;
    }

    private void dimension(
            List<String> conditions,
            Map<String, Object> parameters,
            String column,
            String parameter,
            List<UUID> ids,
            boolean includeUnspecified
    ) {
        List<String> alternatives = new ArrayList<>();
        if (!ids.isEmpty()) {
            alternatives.add(column + " IN (:" + parameter + ")");
            parameters.put(parameter, ids);
        }
        if (includeUnspecified) {
            alternatives.add(column + " IS NULL");
        }
        if (!alternatives.isEmpty()) {
            conditions.add("(" + String.join(" OR ", alternatives) + ")");
        }
    }

    private void products(List<String> conditions, Map<String, Object> parameters, ReportFilters filters) {
        List<String> alternatives = new ArrayList<>();
        if (!filters.productIds().isEmpty()) {
            alternatives.add("""
                    EXISTS (SELECT 1 FROM product_agreements fpa
                            WHERE fpa.interaction_id = i.id AND fpa.archived_at IS NULL AND fpa.product_id IN (:productIds))""");
            parameters.put("productIds", filters.productIds());
        }
        if (filters.includeNoProduct()) {
            alternatives.add("NOT EXISTS (SELECT 1 FROM product_agreements npa WHERE npa.interaction_id = i.id"
                    + " AND npa.archived_at IS NULL)");
        }
        if (!alternatives.isEmpty()) {
            conditions.add("(" + String.join(" OR ", alternatives) + ")");
        }
    }

    private void agreements(List<String> conditions, Map<String, Object> parameters, ReportFilters filters) {
        ReportAgreementFilters agreement = filters.agreement();
        if (agreement.unrestricted()) {
            return;
        }
        List<String> checks = new ArrayList<>();
        if (!agreement.vendorIds().isEmpty()) {
            checks.add("apr.vendor_id IN (:vendorIds)");
            parameters.put("vendorIds", agreement.vendorIds());
        }
        if (agreement.licenseSigned() != null) {
            checks.add(agreement.licenseSigned()
                    ? "apa.license_signed = TRUE"
                    : "(apa.license_signed IS NULL OR apa.license_signed = FALSE)");
        }
        if (agreement.licenseExpiresBy() != null) {
            checks.add("apa.license_expiry_year <= :licenseExpiresBy");
            parameters.put("licenseExpiresBy", agreement.licenseExpiresBy());
        }
        if (!agreement.notTransferred().isEmpty()) {
            checks.add("""
                    NOT EXISTS (SELECT 1 FROM product_transfers apt
                                WHERE apt.agreement_id = apa.id AND apt.status = 'TRANSFERRED'
                                  AND apt.kind IN (:notTransferred))
                    AND (apa.transfer_status IS DISTINCT FROM :transferredStatus
                         OR EXISTS (SELECT 1 FROM product_transfers anm WHERE anm.agreement_id = apa.id))""");
            parameters.put("notTransferred", agreement.notTransferred().stream().map(Enum::name).toList());
            parameters.put("transferredStatus", ProductAgreementRules.TRANSFERRED);
        }
        if (!filters.productIds().isEmpty()) {
            checks.add("apa.product_id IN (:productIds)");
            parameters.put("productIds", filters.productIds());
        }
        conditions.add("EXISTS (SELECT 1 FROM product_agreements apa JOIN products apr ON apr.id = apa.product_id"
                + " WHERE apa.interaction_id = i.id AND apa.archived_at IS NULL AND " + String.join(" AND ", checks) + ")");
    }

    public List<CatalogReference> findVendorOptions() {
        return jdbcClient.sql("SELECT id, name, archived, version FROM vendors ORDER BY archived, name, id")
                .query((resultSet, rowNumber) -> new CatalogReference(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getBoolean("archived"),
                        resultSet.getInt("version")
                ))
                .list();
    }

    private List<ReportRow> withProducts(List<ReportRow> rows) {
        List<UUID> interactionIds = rows.stream().map(ReportRow::interactionId).filter(Objects::nonNull).distinct().toList();
        if (interactionIds.isEmpty()) {
            return rows;
        }
        Map<UUID, List<ReportRow.Product>> products = new HashMap<>();
        Map<UUID, List<ReportRow.Agreement>> agreements = new HashMap<>();
        for (int start = 0; start < interactionIds.size(); start += PRODUCT_BATCH_SIZE) {
            List<UUID> batch = interactionIds.subList(start, Math.min(start + PRODUCT_BATCH_SIZE, interactionIds.size()));
            jdbcClient.sql("""
                    SELECT DISTINCT pa.interaction_id, pr.id, pr.name
                    FROM product_agreements pa
                    JOIN products pr ON pr.id = pa.product_id
                    WHERE pa.interaction_id IN (:interactionIds) AND pa.archived_at IS NULL
                    ORDER BY pr.name, pr.id
                    """)
                    .param("interactionIds", batch)
                    .query((ResultSet resultSet) -> {
                        products.computeIfAbsent(resultSet.getObject("interaction_id", UUID.class), id -> new ArrayList<>())
                                .add(new ReportRow.Product(resultSet.getObject("id", UUID.class), resultSet.getString("name")));
                    });
            jdbcClient.sql("""
                    SELECT pa.interaction_id, pr.name AS product_name, v.name AS vendor_name, pa.contract_number,
                           pa.license_signed, pa.license_expiry_year, pa.transfer_status, pt.transferred_on
                    FROM product_agreements pa
                    JOIN products pr ON pr.id = pa.product_id
                    JOIN vendors v ON v.id = pr.vendor_id
                    LEFT JOIN product_transfers pt ON pt.agreement_id = pa.id AND pt.kind = 'MATERIALS' AND pt.status = 'TRANSFERRED'
                    WHERE pa.interaction_id IN (:interactionIds) AND pa.archived_at IS NULL
                    ORDER BY pr.name, pa.id
                    """)
                    .param("interactionIds", batch)
                    .query((ResultSet resultSet) -> {
                        agreements.computeIfAbsent(resultSet.getObject("interaction_id", UUID.class), id -> new ArrayList<>())
                                .add(new ReportRow.Agreement(
                                        resultSet.getString("product_name"),
                                        resultSet.getString("vendor_name"),
                                        resultSet.getString("contract_number"),
                                        resultSet.getObject("license_signed", Boolean.class),
                                        resultSet.getObject("license_expiry_year", Integer.class),
                                        resultSet.getString("transfer_status"),
                                        resultSet.getObject("transferred_on", LocalDate.class)
                                ));
                    });
        }
        return rows.stream()
                .map(row -> row.withProducts(
                        products.getOrDefault(row.interactionId(), List.of()),
                        agreements.getOrDefault(row.interactionId(), List.of())
                ))
                .toList();
    }

    private ReportRow mapRow(ResultSet resultSet, OffsetDateTime now) throws SQLException {
        OffsetDateTime stageEnteredAt = resultSet.getObject("stage_entered_at", OffsetDateTime.class);
        return new ReportRow(
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getString("interaction_title"),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("organization_name"),
                resultSet.getObject("direction_id", UUID.class),
                resultSet.getString("direction_name"),
                resultSet.getObject("program_id", UUID.class),
                resultSet.getString("program_name"),
                List.of(),
                resultSet.getString("stage_name"),
                resultSet.getObject("manager_id", UUID.class),
                resultSet.getString("manager_name"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("last_event_at", OffsetDateTime.class),
                stageEnteredAt == null ? null : Math.max(0, Duration.between(stageEnteredAt, now).toDays()),
                resultSet.getString("next_action"),
                resultSet.getObject("next_action_at", OffsetDateTime.class),
                resultSet.getObject("event_at", OffsetDateTime.class),
                eventTypeLabel(resultSet.getString("event_type")),
                resultSet.getString("from_stage_name"),
                resultSet.getString("event_comment"),
                resultSet.getString("author_name"),
                null,
                null,
                null,
                mapMarks(resultSet),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private InteractionMarks mapMarks(ResultSet resultSet) throws SQLException {
        String workStatus = resultSet.getString("work_status");
        return workStatus == null ? null : InteractionIssueRepository.mapMarks(resultSet);
    }

    private ReportRow mapDemandRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ReportRow(
                null, null, null, null,
                resultSet.getObject("direction_id", UUID.class),
                resultSet.getString("direction_name"),
                resultSet.getObject("program_id", UUID.class),
                resultSet.getString("program_name"),
                List.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                resultSet.getObject("applications", Long.class),
                resultSet.getObject("participants", Long.class),
                resultSet.getObject("parallel_runs", Long.class),
                null,
                List.of(),
                null,
                resultSet.getObject("completed", Long.class),
                null,
                resultSet.getObject("paid_orders", Long.class),
                resultSet.getObject("paid_streams", Long.class),
                resultSet.getObject("completed_base", Long.class),
                null,
                null,
                resultSet.getObject("runs_without_data", Long.class),
                new ReportRow.Demand(
                        resultSet.getObject("active_7d", Long.class),
                        resultSet.getObject("activity_base", Long.class),
                        resultSet.getObject("stale_runs", Long.class),
                        null,
                        null,
                        null
                )
        );
    }

    private List<ReportRow> findAgreementRows(VisibilityScope scope, ReportRequest request, int limit, long offset) {
        Selection selection = agreementSelection(scope, request);
        List<AgreementRaw> raws = jdbcClient.sql("""
                SELECT o.id AS organization_id, o.name AS organization_name,
                       ag.id AS agreement_id, ag.number, ag.concluded_on, ag.valid_until, ag.status AS agreement_status,
                       ac.id AS activity_id, k.name AS kind_name, ac.title, ac.unit,
                       CAST(ac.planned_volume AS BIGINT) AS planned_volume, CAST(ac.actual_volume AS BIGINT) AS actual_volume,
                       ac.planned_start, ac.planned_end, ac.actual_start, ac.actual_end, ac.status AS activity_status,
                       ac.responsible_profile_id AS manager_id, m.display_name AS manager_name
                """ + AGREEMENTS_FROM + selection.sql() + """
                 ORDER BY o.name, ag.concluded_on NULLS LAST, ag.number, ag.id, k.sort_order NULLS FIRST,
                          ac.planned_start NULLS LAST, ac.title, ac.id
                 LIMIT :limit OFFSET :offset
                """)
                .params(selection.parameters())
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapAgreementRaw)
                .list();
        List<UUID> activityIds = raws.stream().map(AgreementRaw::activityId).filter(Objects::nonNull).toList();
        Map<UUID, List<String>> works = new HashMap<>();
        Map<UUID, List<String>> confirmations = new HashMap<>();
        Map<UUID, List<String>> links = new HashMap<>();
        Map<UUID, Long> participants = new HashMap<>();
        List<String> runConditions = new ArrayList<>(List.of(
                "(" + AgreementRepository.ACTIVITY_START + " IS NULL OR sm.run_ends_on > " + AgreementRepository.ACTIVITY_START + ")",
                "(" + AgreementRepository.ACTIVITY_END + " IS NULL OR sm.run_starts_on <= " + AgreementRepository.ACTIVITY_END + ")"
        ));
        Map<String, Object> runParameters = new HashMap<>(Map.of("learningAt", request.learningAt()));
        if (request.to() != null) {
            runConditions.add("sm.run_starts_on <= :periodTo");
            runParameters.put("periodTo", request.to());
        }
        if (request.from() != null) {
            runConditions.add("sm.run_ends_on > :periodFrom");
            runParameters.put("periodFrom", request.from());
        }
        String runWhere = String.join(" AND ", runConditions);
        for (int start = 0; start < activityIds.size(); start += PRODUCT_BATCH_SIZE) {
            List<UUID> batch = activityIds.subList(start, Math.min(start + PRODUCT_BATCH_SIZE, activityIds.size()));
            jdbcClient.sql("""
                    SELECT ai.activity_id, i.title
                    FROM agreement_activity_interactions ai
                    JOIN interactions i ON i.id = ai.interaction_id
                    WHERE ai.activity_id IN (:ids)
                    ORDER BY i.title, i.id
                    """)
                    .param("ids", batch)
                    .query((ResultSet resultSet) -> {
                        works.computeIfAbsent(resultSet.getObject("activity_id", UUID.class), id -> new ArrayList<>())
                                .add(resultSet.getString("title"));
                    });
            jdbcClient.sql("""
                    SELECT aa.activity_id, a.id, a.original_name, a.created_at
                    FROM agreement_activity_attachments aa
                    JOIN attachments a ON a.id = aa.attachment_id
                    WHERE aa.activity_id IN (:ids) AND a.status = 'CLEAN' AND a.deleted_at IS NULL
                    ORDER BY a.created_at, a.id
                    """)
                    .param("ids", batch)
                    .query((ResultSet resultSet) -> {
                        UUID activityId = resultSet.getObject("activity_id", UUID.class);
                        OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
                        confirmations.computeIfAbsent(activityId, id -> new ArrayList<>()).add(
                                resultSet.getString("original_name") + " ("
                                        + DATE.format(createdAt.atZoneSameInstant(ReportRequest.ZONE)) + ")"
                        );
                        links.computeIfAbsent(activityId, id -> new ArrayList<>()).add(
                                publicBaseUrl + "/api/attachments/" + resultSet.getObject("id", UUID.class) + "/download"
                        );
                    });
            jdbcClient.sql("""
                    SELECT x.activity_id, CAST(SUM(x.participants_count) AS BIGINT) AS participants
                    FROM (
                        SELECT DISTINCT ai.activity_id, s.mapping_id, s.participants_count
                        FROM agreement_activity_interactions ai
                        JOIN interactions i ON i.id = ai.interaction_id
                        JOIN source_mappings sm ON sm.organization_id = i.organization_id AND sm.program_id = i.program_id
                            AND sm.kind IN ('COURSE', 'GROUP') AND sm.run_starts_on IS NOT NULL AND sm.run_kind = 'STUDENTS'
                        JOIN learning_observations s ON s.mapping_id = sm.id
                        JOIN agreement_activities ac ON ac.id = ai.activity_id
                        JOIN agreements ag ON ag.id = ac.agreement_id
                        WHERE ai.activity_id IN (:ids) AND %s AND %s
                    ) x
                    GROUP BY x.activity_id
                    """.formatted(runWhere, OBSERVATION_IN_FORCE))
                    .params(runParameters)
                    .param("ids", batch)
                    .query((ResultSet resultSet) -> {
                        participants.put(resultSet.getObject("activity_id", UUID.class), resultSet.getLong("participants"));
                    });
        }
        return raws.stream().map(raw -> raw.row(
                participants.get(raw.activityId()),
                joined(works.get(raw.activityId())),
                joined(confirmations.get(raw.activityId())),
                joined(links.get(raw.activityId()))
        )).toList();
    }

    private Selection agreementSelection(VisibilityScope scope, ReportRequest request) {
        ReportFilters filters = request.filters();
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.add("ag.organization_id IN (" + scope.currentOrganizations() + ")");
        AgreementRepository.periodCondition(conditions, parameters, request.from(), request.to());
        if (!filters.organizationIds().isEmpty()) {
            conditions.add("o.id IN (:organizationIds)");
            parameters.put("organizationIds", filters.organizationIds());
        }
        if (filters.organizationType() != null) {
            conditions.add("o.type = :organizationType");
            parameters.put("organizationType", filters.organizationType().name());
        }
        dimension(conditions, parameters, "ac.responsible_profile_id", "managerIds", filters.managerIds(), filters.includeNoManager());
        return new Selection(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    private AgreementRaw mapAgreementRaw(ResultSet resultSet, int rowNumber) throws SQLException {
        String activityStatus = resultSet.getString("activity_status");
        return new AgreementRaw(
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getString("organization_name"),
                resultSet.getObject("agreement_id", UUID.class),
                resultSet.getString("number"),
                resultSet.getObject("concluded_on", LocalDate.class),
                resultSet.getObject("valid_until", LocalDate.class),
                AgreementStatus.valueOf(resultSet.getString("agreement_status")).title(),
                resultSet.getObject("activity_id", UUID.class),
                resultSet.getString("kind_name"),
                resultSet.getString("title"),
                resultSet.getString("unit"),
                resultSet.getObject("planned_volume", Long.class),
                resultSet.getObject("actual_volume", Long.class),
                period(resultSet.getObject("planned_start", LocalDate.class), resultSet.getObject("planned_end", LocalDate.class)),
                period(resultSet.getObject("actual_start", LocalDate.class), resultSet.getObject("actual_end", LocalDate.class)),
                activityStatus == null ? null : ActivityStatus.valueOf(activityStatus).title(),
                resultSet.getObject("manager_id", UUID.class),
                resultSet.getString("manager_name")
        );
    }

    private static String joined(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("; ", values);
    }

    private static String period(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return null;
        }
        if (start != null && start.equals(end)) {
            return DATE.format(start);
        }
        return (start == null ? "…" : DATE.format(start)) + " – " + (end == null ? "…" : DATE.format(end));
    }

    private ReportManagerOption mapManager(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ReportManagerOption(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("display_name"),
                resultSet.getBoolean("active")
        );
    }

    private static String eventTypeLabel(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "CREATED" -> "Создание";
            case "TRANSITIONED" -> "Переход";
            case "COMMENTED" -> "Комментарий";
            case "STAGES_EDITED" -> "Изменены этапы карточки";
            case "PLAN_UPDATED" -> "Изменён план";
            case "DETAILS_UPDATED" -> "Изменены данные взаимодействия";
            case "STATUS_CHANGED" -> "Изменён статус взаимодействия";
            case "AGREEMENT_UPDATED" -> "Изменены договор и передача";
            case "ATTACHMENT_DELETED" -> "Удалён документ";
            case "STAGE_COMPLETED" -> "Этап отмечен выполненным";
            case "STAGE_COMPLETION_CLEARED" -> "Снята отметка выполнения этапа";
            case "ASSIGNED" -> "Назначение КАМ";
            case "REASSIGNED" -> "Смена КАМ";
            case "UNASSIGNED" -> "Снятие КАМ";
            default -> type;
        };
    }

    public enum CatalogTable {
        DIRECTIONS("directions"),
        PROGRAMS("programs"),
        PRODUCTS("products"),
        VENDORS("vendors"),
        INTERACTION_KINDS("interaction_kinds");


        private final String tableName;

        CatalogTable(String tableName) {
            this.tableName = tableName;
        }

        String tableName() {
            return tableName;
        }
    }

    public record MarketSource(String dataSource, LocalDate asOf) {
    }

    public record GroupCount(String key, String label, String seriesKey, String seriesLabel, long count) {
    }

    public record StageEntry(
            UUID interactionId,
            OffsetDateTime createdAt,
            UUID teamId,
            String teamName,
            UUID programId,
            String programName,
            String stageName,
            OffsetDateTime occurredAt,
            Integer stageOrder,
            boolean finalStage,
            UUID templateId,
            String templateName
    ) {
        public StageEntry(UUID interactionId, OffsetDateTime createdAt, UUID teamId, String teamName, UUID programId,
                          String programName, String stageName, OffsetDateTime occurredAt, Integer stageOrder, boolean finalStage) {
            this(interactionId, createdAt, teamId, teamName, programId, programName, stageName, occurredAt, stageOrder, finalStage,
                    null, null);
        }
    }

    /**
     * Маршрут работ выборки: шаблон и названия этапов в его порядке. templateId = null — работы без шаблона.
     * formerStages — этапы прежних версий шаблона (они в конце stages); cardStages — этапы, добавленные в маршрут отдельных
     * карточек (стоят в stages после этапа, за которым идут в карточке).
     */
    public record StageRoute(UUID templateId, String templateName, List<String> stages, List<String> formerStages,
                             List<String> cardStages) {
        public StageRoute(UUID templateId, String templateName, List<String> stages, List<String> formerStages) {
            this(templateId, templateName, stages, formerStages, List.of());
        }

        static final String NO_TEMPLATE = "-";

        public String key(String stage) {
            return (templateId == null ? NO_TEMPLATE : templateId.toString()) + "|" + stage;
        }

        public String label(String stage) {
            return (templateName == null ? "Без шаблона" : templateName) + ": " + stage;
        }
    }

    private record GroupColumns(String key, String label) {
    }

    private record Selection(String sql, Map<String, Object> parameters) {
    }

    private record AgreementRaw(
            UUID organizationId,
            String organizationName,
            UUID agreementId,
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            String agreementStatus,
            UUID activityId,
            String kindName,
            String title,
            String unit,
            Long plannedVolume,
            Long actualVolume,
            String plannedDates,
            String actualDates,
            String activityStatus,
            UUID managerId,
            String managerName
    ) {
        ReportRow row(Long participants, String works, String confirmations, String links) {
            return new ReportRow(
                    null, null, organizationId, organizationName, null, null, null, null, List.of(),
                    null, managerId, managerName, null, null, null, null, null, null, null, null, null, null,
                    null, participants, null, null, List.of(), null, null,
                    new ReportRow.AgreementLine(
                            agreementId,
                            activityId,
                            "№ " + number + (concludedOn == null ? "" : " от " + DATE.format(concludedOn)),
                            agreementStatus,
                            period(concludedOn, validUntil),
                            kindName,
                            title,
                            plannedVolume,
                            actualVolume,
                            unit,
                            plannedDates,
                            actualDates,
                            activityStatus,
                            works,
                            confirmations,
                            links
                    ),
                    null,
                    null,
                    null
            );
        }
    }
}
