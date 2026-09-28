package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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

@Repository
public class OrganizationRepository {
    private static final String REQUIRES_ASSIGNMENT = requiresAssignment("organizations");
    private static final String INHERITED = """
            (owner_manager_id IS NOT NULL
             AND EXISTS (
                SELECT 1
                FROM organization_assignment_events handover
                WHERE handover.organization_id = organizations.id
                  AND handover.previous_owner_manager_id IS NOT NULL
                  AND handover.previous_owner_manager_id <> organizations.owner_manager_id
             )
             AND NOT EXISTS (
                SELECT 1
                FROM contacts confirmed
                WHERE confirmed.organization_id = organizations.id
                  AND confirmed.confirmed_by = organizations.owner_manager_id
                  AND confirmed.confirmed_at >= COALESCE((
                      SELECT MAX(received.occurred_at)
                      FROM organization_assignment_events received
                      WHERE received.organization_id = organizations.id
                        AND received.owner_manager_id = organizations.owner_manager_id
                  ), confirmed.confirmed_at)
             ))""";
    private static final String ORGANIZATION_COLUMNS = """
            id, name, type, team_id, owner_manager_id, version, updated_at, status, city, website, inn,
            (SELECT display_name FROM crm_user_profiles owner_profile WHERE owner_profile.id = organizations.owner_manager_id)
                AS owner_manager_name,
            (SELECT team.name FROM teams team WHERE team.id = organizations.team_id) AS team_name,
            CASE WHEN %s THEN TRUE ELSE FALSE END AS requires_assignment,
            CASE WHEN %s THEN TRUE ELSE FALSE END AS inherited,
            (SELECT deputy.deputy_display_name %s) AS deputy_manager_name,
            (SELECT deputy.ends_on %s) AS deputy_ends_on""".formatted(
            REQUIRES_ASSIGNMENT,
            INHERITED,
            activeDeputy("organizations"),
            activeDeputy("organizations")
    );

    private final JdbcClient jdbcClient;

    public OrganizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public OrganizationPage findVisible(CrmProfile profile, OrganizationQuery query) {
        VisibilityScope scope = scopeFor(profile);
        return scope == null ? emptyPage(query) : findByScope(scope, query);
    }

    public Optional<Organization> findVisibleById(CrmProfile profile, UUID organizationId) {
        VisibilityScope scope = scopeFor(profile);
        return scope == null ? Optional.empty() : findOneByScope(scope, organizationId);
    }

    public Optional<VisibilityScope> visibilityScope(CrmProfile profile) {
        return Optional.ofNullable(scopeFor(profile));
    }

    public boolean isArchived(UUID organizationId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM organizations WHERE id = :organizationId AND status = :archived")
                .param("organizationId", organizationId)
                .param("archived", OrganizationStatus.ARCHIVED.name())
                .query(Long.class)
                .single() > 0;
    }

    private OrganizationPage findByScope(VisibilityScope scope, OrganizationQuery query) {
        List<String> conditions = new ArrayList<>(List.of("(" + scope.condition() + ")", query.status().condition("status")));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        if (query.search() != null) {
            conditions.add("LOWER(name) LIKE :search " + SearchPattern.LIKE_ESCAPE);
            parameters.put("search", SearchPattern.contains(query.search()));
        }
        if (query.requiresAssignment()) {
            conditions.add(REQUIRES_ASSIGNMENT);
        }
        String where = String.join(" AND ", conditions);
        List<Organization> items = jdbcClient.sql("""
                SELECT %s
                FROM organizations
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(ORGANIZATION_COLUMNS, where, query.sort().orderBy()))
                .params(parameters)
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapOrganization)
                .list();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM organizations WHERE " + where)
                .params(parameters)
                .query(Long.class)
                .single();
        return new OrganizationPage(items, query.page(), query.size(), total);
    }

    private Optional<Organization> findOneByScope(VisibilityScope scope, UUID organizationId) {
        return jdbcClient.sql("""
                SELECT %s
                FROM organizations
                WHERE id = :organizationId AND %s
                """.formatted(ORGANIZATION_COLUMNS, scope.condition()))
                .param("organizationId", organizationId)
                .params(scope.parameters())
                .query(this::mapOrganization)
                .optional();
    }

    private VisibilityScope scopeFor(CrmProfile profile) {
        return switch (profile.role()) {
            case USER -> profile.teamId() == null
                    ? null
                    : new VisibilityScope(
                            "(team_id = :teamId AND (owner_manager_id = :ownerManagerId OR EXISTS (SELECT 1 "
                                    + activeDeputy("organizations")
                                    + " AND deputy.deputy_profile_id = :ownerManagerId)))",
                            Map.of("ownerManagerId", profile.id(), "teamId", profile.teamId())
                    );
            case LEADER -> profile.teamId() == null
                    ? null
                    : new VisibilityScope("team_id = :teamId", Map.of("teamId", profile.teamId()));
            case MANAGEMENT -> new VisibilityScope("1 = 1", Map.of());
            case ADMIN -> null;
        };
    }

    public static String currentStatus(String organization) {
        return OrganizationListStatus.CURRENT.condition(organization + ".status");
    }

    public static String requiresAssignment(String organization) {
        return """
                (%1$s.type <> 'OPEN_ENROLLMENT' AND (%1$s.owner_manager_id IS NULL OR NOT EXISTS (
                    SELECT 1
                    FROM crm_user_profiles owner_profile
                    WHERE owner_profile.id = %1$s.owner_manager_id
                      AND owner_profile.active = TRUE
                      AND owner_profile.role IN ('USER', 'LEADER')
                      AND owner_profile.team_id = %1$s.team_id
                )))""".formatted(organization);
    }

    public static String activeDeputy(String organization) {
        return """
                FROM organization_deputies deputy
                JOIN crm_user_profiles deputy_profile ON deputy_profile.id = deputy.deputy_profile_id
                WHERE deputy.organization_id = %1$s.id
                  AND deputy.ended_at IS NULL
                  AND deputy.starts_at <= CURRENT_TIMESTAMP
                  AND deputy.ends_at > CURRENT_TIMESTAMP
                  AND deputy_profile.active = TRUE
                  AND deputy_profile.role = 'USER'
                  AND deputy_profile.team_id = %1$s.team_id""".formatted(organization);
    }

    private OrganizationPage emptyPage(OrganizationQuery query) {
        return new OrganizationPage(List.of(), query.page(), query.size(), 0);
    }

    private Organization mapOrganization(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Organization(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                OrganizationType.valueOf(resultSet.getString("type")),
                resultSet.getObject("team_id", UUID.class),
                resultSet.getObject("owner_manager_id", UUID.class),
                resultSet.getInt("version"),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getString("owner_manager_name"),
                resultSet.getString("team_name"),
                resultSet.getBoolean("requires_assignment"),
                OrganizationStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("city"),
                resultSet.getString("website"),
                resultSet.getString("inn"),
                resultSet.getBoolean("inherited"),
                resultSet.getString("deputy_manager_name"),
                resultSet.getObject("deputy_ends_on", LocalDate.class)
        );
    }

    public record VisibilityScope(String condition, Map<String, Object> parameters) {
    }
}
