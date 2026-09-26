package ru.rtk.crm.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminOrganizationRepository {
    private static final String ORGANIZATION_QUERY = """
            SELECT org.id, org.name, org.type, org.team_id, team.name AS team_name,
                   org.owner_manager_id, owner.display_name AS owner_manager_name, org.version,
                   org.status, org.city, org.website, org.inn
            FROM organizations org
            JOIN teams team ON team.id = org.team_id
            LEFT JOIN crm_user_profiles owner ON owner.id = org.owner_manager_id
            """;

    private final JdbcClient jdbcClient;

    public AdminOrganizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AdminOrganizationPage findPage(OrganizationQuery query) {
        String where = " WHERE " + query.status().condition("org.status")
                + (query.search() == null ? "" : " AND LOWER(org.name) LIKE :search " + SearchPattern.LIKE_ESCAPE);
        String search = query.search() == null ? "" : SearchPattern.contains(query.search());
        List<AdminOrganization> items = jdbcClient.sql(ORGANIZATION_QUERY + where + """

                ORDER BY org.name ASC, org.id ASC
                LIMIT :size OFFSET :offset
                """)
                .param("search", search)
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapOrganization)
                .list();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM organizations org" + where)
                .param("search", search)
                .query(Long.class)
                .single();
        return new AdminOrganizationPage(items, query.page(), query.size(), total);
    }

    public Optional<AdminOrganization> findById(UUID organizationId) {
        return jdbcClient.sql(ORGANIZATION_QUERY + " WHERE org.id = :organizationId")
                .param("organizationId", organizationId)
                .query(this::mapOrganization)
                .optional();
    }

    public Optional<AdminOrganization> findByIdForUpdate(UUID organizationId) {
        return jdbcClient.sql("SELECT id FROM organizations WHERE id = :organizationId FOR UPDATE")
                .param("organizationId", organizationId)
                .query(UUID.class)
                .optional()
                .flatMap(this::findById);
    }

    public boolean activeTeamExists(UUID teamId) {
        return jdbcClient.sql("SELECT id FROM teams WHERE id = :teamId AND archived = FALSE FOR UPDATE")
                .param("teamId", teamId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public Optional<UUID> findProfileTeamId(UUID profileId) {
        return jdbcClient.sql("SELECT team_id FROM crm_user_profiles WHERE id = :profileId")
                .param("profileId", profileId)
                .query(UUID.class)
                .optional();
    }

    public boolean updateTeam(
            UUID organizationId,
            int expectedVersion,
            UUID teamId,
            boolean clearOwner,
            OffsetDateTime updatedAt
    ) {
        return jdbcClient.sql("""
                UPDATE organizations
                SET team_id = :teamId,
                    owner_manager_id = CASE WHEN :clearOwner THEN NULL ELSE owner_manager_id END,
                    version = version + 1,
                    updated_at = :updatedAt
                WHERE id = :organizationId AND version = :expectedVersion
                """)
                .param("organizationId", organizationId)
                .param("expectedVersion", expectedVersion)
                .param("teamId", teamId)
                .param("clearOwner", clearOwner)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public void incrementLeaderAccessRevisions(UUID firstTeamId, UUID secondTeamId) {
        jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET access_revision = access_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE role = 'LEADER' AND team_id IN (:firstTeamId, :secondTeamId)
                """)
                .param("firstTeamId", firstTeamId)
                .param("secondTeamId", secondTeamId)
                .update();
    }

    public void insertTeamEvent(
            UUID eventId,
            UUID organizationId,
            UUID commandId,
            UUID previousTeamId,
            UUID teamId,
            UUID previousOwnerManagerId,
            UUID actorProfileId,
            String actorDisplayName,
            String requestId,
            int version,
            OffsetDateTime occurredAt
    ) {
        jdbcClient.sql("""
                INSERT INTO organization_team_events (
                    id, organization_id, command_id, previous_team_id, team_id, previous_owner_manager_id,
                    actor_profile_id, actor_display_name, request_id, version, occurred_at
                ) VALUES (
                    :id, :organizationId, :commandId, :previousTeamId, :teamId, :previousOwnerManagerId,
                    :actorProfileId, :actorDisplayName, :requestId, :version, :occurredAt
                )
                """)
                .param("id", eventId)
                .param("organizationId", organizationId)
                .param("commandId", commandId)
                .param("previousTeamId", previousTeamId)
                .param("teamId", teamId)
                .param("previousOwnerManagerId", previousOwnerManagerId)
                .param("actorProfileId", actorProfileId)
                .param("actorDisplayName", actorDisplayName)
                .param("requestId", requestId)
                .param("version", version)
                .param("occurredAt", occurredAt)
                .update();
    }

    private AdminOrganization mapOrganization(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AdminOrganization(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                OrganizationType.valueOf(resultSet.getString("type")),
                resultSet.getObject("team_id", UUID.class),
                resultSet.getString("team_name"),
                resultSet.getObject("owner_manager_id", UUID.class),
                resultSet.getString("owner_manager_name"),
                resultSet.getInt("version"),
                OrganizationStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("city"),
                resultSet.getString("website"),
                resultSet.getString("inn")
        );
    }
}
