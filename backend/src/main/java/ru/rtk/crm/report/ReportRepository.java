package ru.rtk.crm.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionMarks;
import ru.rtk.crm.interaction.InteractionRepository;
import ru.rtk.crm.interaction.InteractionRiskLevel;
import ru.rtk.crm.interaction.InteractionWaiting;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.interaction.ProductAgreementRules;

@Repository
public class ReportRepository {
    private static final int PRODUCT_BATCH_SIZE = 500;
    private static final String NO_ROWS = "1 = 0";
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
                   i.work_status, i.work_status_reason, i.waiting_on, i.waiting_note, i.problem, i.risk_level, i.risk_reason,
                   o.owner_manager_id AS manager_id, m.display_name AS manager_name,
                   i.created_at, i.next_action, i.next_action_at,
                   (SELECT MAX(le.occurred_at) FROM interaction_events le WHERE le.interaction_id = i.id) AS last_event_at,
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
                   i.work_status, i.work_status_reason, i.waiting_on, i.waiting_note, i.problem, i.risk_level, i.risk_reason,
                   sn.manager_id AS manager_id, m.display_name AS manager_name,
                   i.created_at, i.next_action, i.next_action_at,
                   sn.last_event_at AS last_event_at,
            """ + NO_STAGE_ENTERED + EVENT_NULLS;

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
                        WHERE le.interaction_id = si.id AND le.occurred_at < :asOfAt) AS last_event_at
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
                   i.work_status, i.work_status_reason, i.waiting_on, i.waiting_note, i.problem, i.risk_level, i.risk_reason,
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
                   CAST(NULL AS VARCHAR(1000)) AS problem, CAST(NULL AS VARCHAR(16)) AS risk_level,
                   CAST(NULL AS VARCHAR(1000)) AS risk_reason,
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
            """;

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
        if (request.kind() == ReportKind.DEMAND) {
            return findDemandRows(scope.get(), request, limit, offset);
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
        if (request.kind() == ReportKind.DEMAND) {
            Selection selection = demandUnion(scope.get(), request);
            return jdbcClient.sql("SELECT COUNT(*) FROM (SELECT x.program_id FROM (" + selection.sql()
                            + ") x GROUP BY x.program_id) demand_rows")
                    .params(selection.parameters())
                    .query(Long.class)
                    .single();
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
            case DEMAND, DURATION, AGREEMENTS -> throw new IllegalArgumentException("Rows of " + request.kind() + " are not counted here");
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

    public Optional<OffsetDateTime> findLearningObservedAt(CrmProfile profile, ReportRequest request) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Selection selection = learningSelection(scope.get(), request);
        return jdbcClient.sql("SELECT MAX(s.observed_at) " + LEARNING_FROM + selection.sql())
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
        String rows = groupSelect(groupColumns(kind, groupBy), seriesBy == null ? null : groupColumns(kind, seriesBy),
                kind == ReportKind.EVENTS ? "e.id" : "i.id")
                + switch (kind) {
                    case EVENTS -> EVENTS_FROM;
                    case SNAPSHOT -> SNAPSHOT_FROM;
                    default -> PORTFOLIO_FROM;
                }
                + (products ? PRODUCT_JOIN : "")
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
                               CASE WHEN st.stage_order = fin.final_order THEN 1 ELSE 0 END AS final_stage
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
                        resultSet.getInt("final_stage") == 1
                ))
                .list();
    }

    private List<ReportRow> findDemandRows(VisibilityScope scope, ReportRequest request, int limit, long offset) {
        Selection selection = demandUnion(scope, request);
        String metric = switch (request.sortBy()) {
            case PARTICIPANTS -> "participants";
            case PARALLEL_RUNS -> "parallel_runs";
            case LEARNERS_COMPLETED -> "completed";
            default -> "applications";
        };
        return jdbcClient.sql("""
                        SELECT d.id AS direction_id, d.name AS direction_name, p.id AS program_id, p.name AS program_name,
                               CAST(SUM(x.applications) AS BIGINT) AS applications,
                               CAST(SUM(x.participants) AS BIGINT) AS participants,
                               CAST(SUM(x.completed) AS BIGINT) AS completed,
                               CAST(SUM(x.parallel_runs) AS BIGINT) AS parallel_runs
                        FROM (""" + selection.sql() + """
                        ) x
                        LEFT JOIN programs p ON p.id = x.program_id
                        LEFT JOIN directions d ON d.id = p.direction_id
                        GROUP BY d.id, d.name, p.id, p.name
                        ORDER BY %s DESC NULLS LAST, p.name NULLS LAST, d.name, p.id
                        LIMIT :limit OFFSET :offset
                        """.formatted(metric))
                .params(selection.parameters())
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapDemandRow)
                .list();
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
        };
    }

    private static GroupColumns assignmentGroupColumns(StatisticsGroupBy groupBy) {
        return switch (groupBy) {
            case ORGANIZATION -> new GroupColumns("o.id", "o.name");
            case MANAGER -> new GroupColumns("ae.owner_manager_id", "m.display_name");
            case MONTH -> month("ae.occurred_at");
            case STAGE, DIRECTION, PROGRAM, PRODUCT -> new GroupColumns("NULL", "NULL");
        };
    }

    private static GroupColumns demandGroupColumns(StatisticsGroupBy groupBy) {
        return switch (groupBy) {
            case ORGANIZATION -> new GroupColumns("o.id", "o.name");
            case DIRECTION -> new GroupColumns("d.id", "d.name");
            case PROGRAM -> new GroupColumns("p.id", "p.name");
            case MANAGER -> new GroupColumns("o.owner_manager_id", "m.display_name");
            case MONTH -> month("r.submitted_at");
            case STAGE, PRODUCT -> throw new IllegalArgumentException("Unsupported demand grouping " + groupBy);
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
        return jdbcClient.sql("""
                SELECT st.name
                FROM interaction_stages st
                JOIN interactions i ON i.id = st.interaction_id
                WHERE i.organization_id IN (SELECT id FROM organizations WHERE %s)
                GROUP BY st.name
                ORDER BY MIN(st.stage_order), st.name
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
            case DEMAND, DURATION, AGREEMENTS -> throw new IllegalArgumentException("Rows of " + request.kind() + " are not selected here");
        };
    }

    private Selection selection(VisibilityScope scope, ReportRequest request, OffsetDateTime now) {
        ReportKind kind = request.kind();
        ReportFilters filters = request.filters();
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.add("i.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")");
        period(conditions, parameters, request);
        organizationFilters(conditions, parameters, filters);
        dimension(conditions, parameters, "d.id", "directionIds", filters.directionIds(), filters.includeNoDirection());
        dimension(conditions, parameters, "i.program_id", "programIds", filters.programIds(), filters.includeNoProgram());
        dimension(conditions, parameters, managerColumn(kind), "managerIds", filters.managerIds(), filters.includeNoManager());
        products(conditions, parameters, filters);
        agreements(conditions, parameters, filters);
        if (!filters.stages().isEmpty() && kind != ReportKind.DURATION) {
            conditions.add(stageColumn(kind) + " IN (:stages)");
            parameters.put("stages", filters.stages());
        }
        if (!filters.workStatuses().isEmpty()) {
            conditions.add("i.work_status IN (:workStatuses)");
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
        conditions.add("ae.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")");
        conditions.addAll(bounds("ae.occurred_at", request, parameters));
        organizationFilters(conditions, parameters, filters);
        dimension(conditions, parameters, "ae.owner_manager_id", "managerIds", filters.managerIds(), filters.includeNoManager());
        boolean excluded = profile.role() != UserRole.LEADER && profile.role() != UserRole.MANAGEMENT
                || !filters.eventTypes().isEmpty() && !filters.eventTypes().contains(ReportEventType.ASSIGNMENT)
                || onlySpecified(filters.directionIds(), filters.includeNoDirection())
                || onlySpecified(filters.programIds(), filters.includeNoProgram())
                || onlySpecified(filters.productIds(), filters.includeNoProduct())
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
        Map<String, Object> parameters = merged(applications, learning);
        parameters.put("runsAsOf", request.runsAsOf());
        return new Selection(
                "SELECT r.program_id, CAST(r.applications_count AS BIGINT) AS applications, CAST(NULL AS BIGINT) AS participants,"
                        + " CAST(NULL AS BIGINT) AS completed, CAST(NULL AS BIGINT) AS parallel_runs " + DEMAND_FROM
                        + applications.sql()
                        + " UNION ALL SELECT s.program_id, CAST(NULL AS BIGINT), CAST(s.participants_count AS BIGINT),"
                        + " CAST(s.completed_count AS BIGINT),"
                        + " CAST(CASE WHEN sm.run_starts_on <= :runsAsOf AND :runsAsOf < sm.run_ends_on THEN 1 ELSE 0 END"
                        + " AS BIGINT) " + LEARNING_FROM + learning.sql(),
                parameters
        );
    }

    private Selection learningSelection(VisibilityScope scope, ReportRequest request) {
        List<String> conditions = new ArrayList<>(List.of(
                "s.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
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
                "r.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")"
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
            case SNAPSHOT, DURATION -> {
            }
        }
    }

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
                null
        );
    }

    private InteractionMarks mapMarks(ResultSet resultSet) throws SQLException {
        String workStatus = resultSet.getString("work_status");
        if (workStatus == null) {
            return null;
        }
        String waitingOn = resultSet.getString("waiting_on");
        String riskLevel = resultSet.getString("risk_level");
        return new InteractionMarks(
                InteractionWorkStatus.valueOf(workStatus),
                resultSet.getString("work_status_reason"),
                waitingOn == null ? null : InteractionWaiting.valueOf(waitingOn),
                resultSet.getString("waiting_note"),
                resultSet.getString("problem"),
                riskLevel == null ? null : InteractionRiskLevel.valueOf(riskLevel),
                resultSet.getString("risk_reason")
        );
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
                null
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
        Map<String, Object> runParameters = new HashMap<>();
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
                        SELECT DISTINCT ai.activity_id, s.source_record_id, s.participants_count
                        FROM agreement_activity_interactions ai
                        JOIN interactions i ON i.id = ai.interaction_id
                        JOIN learning_snapshots s ON s.organization_id = i.organization_id AND s.program_id = i.program_id
                        JOIN source_records sr ON sr.id = s.source_record_id
                        JOIN source_mappings sm ON sm.source = sr.source AND sm.external_key = sr.external_id
                            AND sm.kind = CASE WHEN s.group_id IS NULL THEN 'COURSE' ELSE 'GROUP' END
                            AND sm.run_starts_on IS NOT NULL
                        JOIN agreement_activities ac ON ac.id = ai.activity_id
                        JOIN agreements ag ON ag.id = ac.agreement_id
                        WHERE ai.activity_id IN (:ids) AND %s
                    ) x
                    GROUP BY x.activity_id
                    """.formatted(runWhere))
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
        conditions.add("ag.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")");
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
            case "DETAILS_UPDATED" -> "Изменены данные работы";
            case "STATUS_CHANGED" -> "Изменён статус работы";
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
        VENDORS("vendors");

        private final String tableName;

        CatalogTable(String tableName) {
            this.tableName = tableName;
        }

        String tableName() {
            return tableName;
        }
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
            boolean finalStage
    ) {
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
                    )
            );
        }
    }
}
