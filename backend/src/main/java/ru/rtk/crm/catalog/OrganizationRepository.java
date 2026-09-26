package ru.rtk.crm.catalog;

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

@Repository
public class OrganizationRepository {
    private static final String REQUIRES_ASSIGNMENT = """
            (owner_manager_id IS NULL OR NOT EXISTS (
                SELECT 1
                FROM crm_user_profiles owner_profile
                WHERE owner_profile.id = organizations.owner_manager_id
                  AND owner_profile.active = TRUE
                  AND owner_profile.role = 'USER'
                  AND owner_profile.team_id = organizations.team_id
            ))""";
    private static final String ORGANIZATION_COLUMNS = """
            id, name, type, team_id, owner_manager_id, version, updated_at,
            (SELECT display_name FROM crm_user_profiles owner_profile WHERE owner_profile.id = organizations.owner_manager_id)
                AS owner_manager_name,
            (SELECT team.name FROM teams team WHERE team.id = organizations.team_id) AS team_name,
            CASE WHEN %s THEN TRUE ELSE FALSE END AS requires_assignment""".formatted(REQUIRES_ASSIGNMENT);

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

    private OrganizationPage findByScope(VisibilityScope scope, OrganizationQuery query) {
        List<String> conditions = new ArrayList<>(List.of("(" + scope.condition() + ")"));
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
                            "owner_manager_id = :ownerManagerId AND team_id = :teamId",
                            Map.of("ownerManagerId", profile.id(), "teamId", profile.teamId())
                    );
            case LEADER -> profile.teamId() == null
                    ? null
                    : new VisibilityScope("team_id = :teamId", Map.of("teamId", profile.teamId()));
            case ADMIN -> null;
        };
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
                resultSet.getBoolean("requires_assignment")
        );
    }

    public record VisibilityScope(String condition, Map<String, Object> parameters) {
    }
}
