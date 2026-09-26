package ru.rtk.crm.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class ReportRepository {
    private static final int PRODUCT_BATCH_SIZE = 500;

    private static final String PORTFOLIO_SELECT = """
            SELECT i.id AS interaction_id, i.title AS interaction_title,
                   o.id AS organization_id, o.name AS organization_name,
                   d.id AS direction_id, d.name AS direction_name,
                   p.id AS program_id, p.name AS program_name,
                   s.name AS stage_name,
                   o.owner_manager_id AS manager_id, m.display_name AS manager_name,
                   i.created_at, i.next_action, i.next_action_at,
                   (SELECT MAX(le.occurred_at) FROM interaction_events le WHERE le.interaction_id = i.id) AS last_event_at,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS event_at,
                   CAST(NULL AS VARCHAR(32)) AS event_type,
                   CAST(NULL AS VARCHAR(200)) AS from_stage_name,
                   CAST(NULL AS VARCHAR(4000)) AS event_comment,
                   CAST(NULL AS VARCHAR(200)) AS author_name
            """;

    private static final String PORTFOLIO_FROM = """
            FROM interactions i
            JOIN organizations o ON o.id = i.organization_id
            JOIN interaction_stages s ON s.id = i.current_stage_id AND s.interaction_id = i.id
            LEFT JOIN crm_user_profiles m ON m.id = o.owner_manager_id
            LEFT JOIN programs p ON p.id = i.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String EVENTS_SELECT = """
            SELECT i.id AS interaction_id, i.title AS interaction_title,
                   o.id AS organization_id, o.name AS organization_name,
                   d.id AS direction_id, d.name AS direction_name,
                   p.id AS program_id, p.name AS program_name,
                   e.stage_name_snapshot AS stage_name,
                   e.owner_manager_id_snapshot AS manager_id, m.display_name AS manager_name,
                   i.created_at, i.next_action, i.next_action_at,
                   CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS last_event_at,
                   e.occurred_at AS event_at,
                   e.type AS event_type,
                   e.from_stage_name_snapshot AS from_stage_name,
                   e.comment AS event_comment,
                   a.display_name AS author_name
            """;

    private static final String EVENTS_FROM = """
            FROM interaction_events e
            JOIN interactions i ON i.id = e.interaction_id
            JOIN organizations o ON o.id = i.organization_id
            JOIN crm_user_profiles a ON a.id = e.actor_profile_id
            LEFT JOIN crm_user_profiles m ON m.id = e.owner_manager_id_snapshot
            LEFT JOIN programs p ON p.id = i.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String DEMAND_FROM = """
            FROM source_records r
            JOIN organizations o ON o.id = r.organization_id
            LEFT JOIN crm_user_profiles m ON m.id = o.owner_manager_id
            LEFT JOIN programs p ON p.id = r.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private static final String LEARNING_FROM = """
            FROM learning_snapshots s
            JOIN source_records sr ON sr.id = s.source_record_id
            JOIN source_mappings sm ON sm.source = sr.source AND sm.external_key = sr.external_id
                AND sm.kind = CASE WHEN s.group_id IS NULL THEN 'COURSE' ELSE 'GROUP' END AND sm.run_starts_on IS NOT NULL
            JOIN organizations o ON o.id = s.organization_id
            JOIN programs p ON p.id = s.program_id
            LEFT JOIN directions d ON d.id = p.direction_id
            """;

    private final JdbcClient jdbcClient;
    private final OrganizationRepository organizationRepository;

    public ReportRepository(JdbcClient jdbcClient, OrganizationRepository organizationRepository) {
        this.jdbcClient = jdbcClient;
        this.organizationRepository = organizationRepository;
    }

    public List<ReportRow> findRows(CrmProfile profile, ReportRequest request, int limit, long offset) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        if (request.kind() == ReportKind.DEMAND) {
            return findDemandRows(scope.get(), request, limit, offset);
        }
        Selection selection = selection(scope.get(), request);
        boolean events = request.kind() == ReportKind.EVENTS;
        String sql = (events ? EVENTS_SELECT + EVENTS_FROM : PORTFOLIO_SELECT + PORTFOLIO_FROM)
                + selection.where()
                + (events ? " ORDER BY e.occurred_at, e.id" : " ORDER BY o.name, i.created_at, i.id")
                + " LIMIT :limit OFFSET :offset";
        List<ReportRow> rows = jdbcClient.sql(sql)
                .params(selection.parameters())
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapRow)
                .list();
        return withProducts(rows);
    }

    public long countRows(CrmProfile profile, ReportRequest request) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return 0;
        }
        if (request.kind() == ReportKind.DEMAND) {
            Selection selection = demandUnion(scope.get(), request);
            return jdbcClient.sql("SELECT COUNT(*) FROM (SELECT x.program_id FROM (" + selection.where()
                            + ") x GROUP BY x.program_id) demand_rows")
                    .params(selection.parameters())
                    .query(Long.class)
                    .single();
        }
        Selection selection = selection(scope.get(), request);
        String from = request.kind() == ReportKind.EVENTS ? EVENTS_FROM : PORTFOLIO_FROM;
        return jdbcClient.sql("SELECT COUNT(*) " + from + selection.where())
                .params(selection.parameters())
                .query(Long.class)
                .single();
    }

    public long sumApplications(CrmProfile profile, ReportRequest request) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return 0;
        }
        Selection selection = demandSelection(scope.get(), request);
        return jdbcClient.sql("SELECT COALESCE(SUM(r.applications_count), 0) " + DEMAND_FROM + selection.where())
                .params(selection.parameters())
                .query(Long.class)
                .single();
    }

    public List<GroupCount> countGroups(CrmProfile profile, ReportRequest request, StatisticsGroupBy groupBy) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        if (request.kind() == ReportKind.DEMAND) {
            return countDemandGroups(scope.get(), request, groupBy);
        }
        Selection selection = selection(scope.get(), request);
        boolean events = request.kind() == ReportKind.EVENTS;
        String month = "CAST(EXTRACT(YEAR FROM %1$s AT TIME ZONE '%2$s') * 100 + EXTRACT(MONTH FROM %1$s AT TIME ZONE '%2$s') AS INTEGER)"
                .formatted(events ? "e.occurred_at" : "i.created_at", ReportRequest.ZONE.getId());
        String stage = events ? "e.stage_name_snapshot" : "s.name";
        GroupColumns group = switch (groupBy) {
            case STAGE -> new GroupColumns(stage, stage);
            case ORGANIZATION -> new GroupColumns("o.id", "o.name");
            case DIRECTION -> new GroupColumns("d.id", "d.name");
            case PROGRAM -> new GroupColumns("p.id", "p.name");
            case PRODUCT -> new GroupColumns("pr.id", "pr.name");
            case MANAGER -> new GroupColumns(events ? "e.owner_manager_id_snapshot" : "o.owner_manager_id", "m.display_name");
            case MONTH -> new GroupColumns(month, month);
        };
        String sql = "SELECT " + group.key() + " AS group_key, " + group.label() + " AS group_label, COUNT(DISTINCT "
                + (events ? "e.id" : "i.id") + ") AS row_count "
                + (events ? EVENTS_FROM : PORTFOLIO_FROM)
                + (groupBy == StatisticsGroupBy.PRODUCT
                        ? " LEFT JOIN product_agreements pa ON pa.interaction_id = i.id LEFT JOIN products pr ON pr.id = pa.product_id"
                        : "")
                + selection.where()
                + " GROUP BY " + group.key() + ", " + group.label();
        return jdbcClient.sql(sql)
                .params(selection.parameters())
                .query((resultSet, rowNumber) -> new GroupCount(
                        resultSet.getString("group_key"),
                        resultSet.getString("group_label"),
                        resultSet.getLong("row_count")
                ))
                .list();
    }

    private List<ReportRow> findDemandRows(VisibilityScope scope, ReportRequest request, int limit, long offset) {
        Selection selection = demandUnion(scope, request);
        String metric = switch (request.sortBy()) {
            case PARTICIPANTS -> "participants";
            case PARALLEL_RUNS -> "parallel_runs";
            default -> "applications";
        };
        return jdbcClient.sql("""
                        SELECT d.id AS direction_id, d.name AS direction_name, p.id AS program_id, p.name AS program_name,
                               CAST(SUM(x.applications) AS BIGINT) AS applications,
                               CAST(SUM(x.participants) AS BIGINT) AS participants,
                               CAST(SUM(x.parallel_runs) AS BIGINT) AS parallel_runs
                        FROM (""" + selection.where() + """
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

    private List<GroupCount> countDemandGroups(VisibilityScope scope, ReportRequest request, StatisticsGroupBy groupBy) {
        Selection selection = demandSelection(scope, request);
        String month = "CAST(EXTRACT(YEAR FROM r.submitted_at AT TIME ZONE '%1$s') * 100"
                + " + EXTRACT(MONTH FROM r.submitted_at AT TIME ZONE '%1$s') AS INTEGER)";
        GroupColumns group = switch (groupBy) {
            case ORGANIZATION -> new GroupColumns("o.id", "o.name");
            case DIRECTION -> new GroupColumns("d.id", "d.name");
            case PROGRAM -> new GroupColumns("p.id", "p.name");
            case MANAGER -> new GroupColumns("o.owner_manager_id", "m.display_name");
            case MONTH -> new GroupColumns(month.formatted(ReportRequest.ZONE.getId()), month.formatted(ReportRequest.ZONE.getId()));
            case STAGE, PRODUCT -> throw new IllegalArgumentException("Unsupported demand grouping " + groupBy);
        };
        return jdbcClient.sql("SELECT " + group.key() + " AS group_key, " + group.label() + " AS group_label, "
                        + "SUM(r.applications_count) AS row_count " + DEMAND_FROM + selection.where()
                        + " GROUP BY " + group.key() + ", " + group.label())
                .params(selection.parameters())
                .query((resultSet, rowNumber) -> new GroupCount(
                        resultSet.getString("group_key"),
                        resultSet.getString("group_label"),
                        resultSet.getLong("row_count")
                ))
                .list();
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

    private Selection selection(VisibilityScope scope, ReportRequest request) {
        boolean events = request.kind() == ReportKind.EVENTS;
        ReportFilters filters = request.filters();
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        conditions.add("i.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")");
        period(conditions, parameters, request);
        if (!filters.organizationIds().isEmpty()) {
            conditions.add("o.id IN (:organizationIds)");
            parameters.put("organizationIds", filters.organizationIds());
        }
        if (filters.organizationType() != null) {
            conditions.add("o.type = :organizationType");
            parameters.put("organizationType", filters.organizationType().name());
        }
        dimension(conditions, parameters, "d.id", "directionIds", filters.directionIds(), filters.includeNoDirection());
        dimension(conditions, parameters, "i.program_id", "programIds", filters.programIds(), filters.includeNoProgram());
        dimension(
                conditions,
                parameters,
                events ? "e.owner_manager_id_snapshot" : "o.owner_manager_id",
                "managerIds",
                filters.managerIds(),
                filters.includeNoManager()
        );
        products(conditions, parameters, filters);
        if (!filters.stages().isEmpty()) {
            conditions.add((events ? "e.stage_name_snapshot" : "s.name") + " IN (:stages)");
            parameters.put("stages", filters.stages());
        }
        return new Selection(" WHERE " + String.join(" AND ", conditions), parameters);
    }

    private Selection demandUnion(VisibilityScope scope, ReportRequest request) {
        Selection applications = demandSelection(scope, request);
        Selection learning = learningSelection(scope, request);
        Map<String, Object> parameters = new HashMap<>(applications.parameters());
        parameters.putAll(learning.parameters());
        parameters.put("runsAsOf", request.runsAsOf());
        return new Selection(
                "SELECT r.program_id, CAST(r.applications_count AS BIGINT) AS applications, CAST(NULL AS BIGINT) AS participants,"
                        + " CAST(NULL AS BIGINT) AS parallel_runs " + DEMAND_FROM + applications.where()
                        + " UNION ALL SELECT s.program_id, CAST(NULL AS BIGINT), CAST(s.participants_count AS BIGINT),"
                        + " CAST(CASE WHEN sm.run_starts_on <= :runsAsOf AND :runsAsOf < sm.run_ends_on THEN 1 ELSE 0 END"
                        + " AS BIGINT) " + LEARNING_FROM + learning.where(),
                parameters
        );
    }

    private Selection learningSelection(VisibilityScope scope, ReportRequest request) {
        List<String> conditions = new ArrayList<>(List.of(
                "s.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
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
        if (!filters.organizationIds().isEmpty()) {
            conditions.add("o.id IN (:organizationIds)");
            parameters.put("organizationIds", filters.organizationIds());
        }
        if (filters.organizationType() != null) {
            conditions.add("o.type = :organizationType");
            parameters.put("organizationType", filters.organizationType().name());
        }
        dimension(conditions, parameters, "d.id", "directionIds", filters.directionIds(), filters.includeNoDirection());
        dimension(conditions, parameters, programColumn, "programIds", filters.programIds(), filters.includeNoProgram());
        dimension(conditions, parameters, "o.owner_manager_id", "managerIds", filters.managerIds(), filters.includeNoManager());
    }

    private void period(List<String> conditions, Map<String, Object> parameters, ReportRequest request) {
        OffsetDateTime fromAt = request.fromAt();
        OffsetDateTime toAt = request.toAt();
        if (fromAt == null && toAt == null) {
            return;
        }
        String column = switch (request.kind()) {
            case EVENTS -> "e.occurred_at";
            case DEMAND -> "r.submitted_at";
            case PORTFOLIO -> request.periodBasis() == PeriodBasis.ACTIVITY ? "pe.occurred_at" : "i.created_at";
        };
        List<String> bounds = new ArrayList<>();
        if (fromAt != null) {
            bounds.add(column + " >= :fromAt");
            parameters.put("fromAt", fromAt);
        }
        if (toAt != null) {
            bounds.add(column + " < :toAt");
            parameters.put("toAt", toAt);
        }
        String bound = String.join(" AND ", bounds);
        if (request.kind() == ReportKind.PORTFOLIO && request.periodBasis() == PeriodBasis.ACTIVITY) {
            conditions.add("EXISTS (SELECT 1 FROM interaction_events pe WHERE pe.interaction_id = i.id AND " + bound + ")");
        } else {
            conditions.add(bound);
        }
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
                            WHERE fpa.interaction_id = i.id AND fpa.product_id IN (:productIds))""");
            parameters.put("productIds", filters.productIds());
        }
        if (filters.includeNoProduct()) {
            alternatives.add("NOT EXISTS (SELECT 1 FROM product_agreements npa WHERE npa.interaction_id = i.id)");
        }
        if (!alternatives.isEmpty()) {
            conditions.add("(" + String.join(" OR ", alternatives) + ")");
        }
    }

    private List<ReportRow> withProducts(List<ReportRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<UUID> interactionIds = rows.stream().map(ReportRow::interactionId).distinct().toList();
        Map<UUID, List<ReportRow.Product>> products = new HashMap<>();
        for (int start = 0; start < interactionIds.size(); start += PRODUCT_BATCH_SIZE) {
            List<UUID> batch = interactionIds.subList(start, Math.min(start + PRODUCT_BATCH_SIZE, interactionIds.size()));
            jdbcClient.sql("""
                    SELECT DISTINCT pa.interaction_id, pr.id, pr.name
                    FROM product_agreements pa
                    JOIN products pr ON pr.id = pa.product_id
                    WHERE pa.interaction_id IN (:interactionIds)
                    ORDER BY pr.name, pr.id
                    """)
                    .param("interactionIds", batch)
                    .query((ResultSet resultSet) -> {
                        products.computeIfAbsent(resultSet.getObject("interaction_id", UUID.class), id -> new ArrayList<>())
                                .add(new ReportRow.Product(resultSet.getObject("id", UUID.class), resultSet.getString("name")));
                    });
        }
        return rows.stream()
                .map(row -> row.withProducts(products.getOrDefault(row.interactionId(), List.of())))
                .toList();
    }

    private ReportRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
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
                resultSet.getString("next_action"),
                resultSet.getObject("next_action_at", OffsetDateTime.class),
                resultSet.getObject("event_at", OffsetDateTime.class),
                eventTypeLabel(resultSet.getString("event_type")),
                resultSet.getString("from_stage_name"),
                resultSet.getString("event_comment"),
                resultSet.getString("author_name"),
                null,
                null,
                null
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
                null, null, null, null, null, null, null, null, null, null, null, null,
                resultSet.getObject("applications", Long.class),
                resultSet.getObject("participants", Long.class),
                resultSet.getObject("parallel_runs", Long.class)
        );
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
            default -> type;
        };
    }

    public enum CatalogTable {
        DIRECTIONS("directions"),
        PROGRAMS("programs"),
        PRODUCTS("products");

        private final String tableName;

        CatalogTable(String tableName) {
            this.tableName = tableName;
        }

        String tableName() {
            return tableName;
        }
    }

    public record GroupCount(String key, String label, long count) {
    }

    private record GroupColumns(String key, String label) {
    }

    private record Selection(String where, Map<String, Object> parameters) {
    }
}
