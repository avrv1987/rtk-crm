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
public class OrganizationAssignmentRepository {
    private final JdbcClient jdbcClient;

    public OrganizationAssignmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<OrganizationAssignmentCandidate> findCandidates(UUID teamId, UUID leaderId) {
        return jdbcClient.sql("""
                SELECT id, display_name
                FROM crm_user_profiles
                WHERE team_id = :teamId AND active = TRUE
                  AND (role = 'USER' OR (role = 'LEADER' AND id = :leaderId))
                ORDER BY display_name ASC, id ASC
                """)
                .param("teamId", teamId)
                .param("leaderId", leaderId)
                .query((resultSet, rowNumber) -> new OrganizationAssignmentCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name")
                ))
                .list();
    }

    public Optional<OrganizationAssignmentCandidate> findCandidate(UUID profileId, UUID teamId, UUID leaderId) {
        return jdbcClient.sql("""
                SELECT id, display_name
                FROM crm_user_profiles
                WHERE id = :profileId AND team_id = :teamId AND active = TRUE
                  AND (role = 'USER' OR (role = 'LEADER' AND id = :leaderId))
                """)
                .param("profileId", profileId)
                .param("teamId", teamId)
                .param("leaderId", leaderId)
                .query((resultSet, rowNumber) -> new OrganizationAssignmentCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name")
                ))
                .optional();
    }

    public Optional<OrganizationAssignmentProfile> lockProfileForUpdate(UUID profileId) {
        return jdbcClient.sql("""
                SELECT id, display_name, role, team_id, active
                FROM crm_user_profiles
                WHERE id = :profileId
                FOR UPDATE
                """)
                .param("profileId", profileId)
                .query((resultSet, rowNumber) -> new OrganizationAssignmentProfile(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name"),
                        ru.rtk.crm.access.UserRole.valueOf(resultSet.getString("role")),
                        resultSet.getObject("team_id", UUID.class),
                        resultSet.getBoolean("active")
                ))
                .optional();
    }

    public Optional<String> findDisplayName(UUID profileId) {
        return jdbcClient.sql("SELECT display_name FROM crm_user_profiles WHERE id = :profileId")
                .param("profileId", profileId)
                .query(String.class)
                .optional();
    }

    public boolean updateOwner(
            UUID organizationId,
            UUID teamId,
            int expectedVersion,
            UUID ownerManagerId,
            UUID leaderId,
            OffsetDateTime updatedAt
    ) {
        return jdbcClient.sql("""
                UPDATE organizations
                SET owner_manager_id = :ownerManagerId, version = version + 1, updated_at = :updatedAt
                WHERE id = :organizationId AND team_id = :teamId AND version = :expectedVersion
                  AND (
                      CAST(:ownerManagerId AS UUID) IS NULL OR EXISTS (
                          SELECT 1
                          FROM crm_user_profiles candidate
                          WHERE candidate.id = :ownerManagerId
                            AND candidate.team_id = organizations.team_id
                            AND candidate.active = TRUE
                            AND (candidate.role = 'USER' OR (candidate.role = 'LEADER' AND candidate.id = :leaderId))
                      )
                  )
                """)
                .param("organizationId", organizationId)
                .param("teamId", teamId)
                .param("expectedVersion", expectedVersion)
                .param("ownerManagerId", ownerManagerId)
                .param("leaderId", leaderId)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public void incrementAccessRevisions(UUID previousOwnerManagerId, UUID ownerManagerId) {
        incrementAccessRevision(previousOwnerManagerId);
        if (ownerManagerId != null && !ownerManagerId.equals(previousOwnerManagerId)) {
            incrementAccessRevision(ownerManagerId);
        }
    }

    public List<OrganizationAssignmentTarget> lockOwnedOrganizations(UUID ownerManagerId) {
        return jdbcClient.sql("""
                SELECT id, owner_manager_id, version
                FROM organizations
                WHERE owner_manager_id = :ownerManagerId
                ORDER BY id ASC
                FOR UPDATE
                """)
                .param("ownerManagerId", ownerManagerId)
                .query((resultSet, rowNumber) -> new OrganizationAssignmentTarget(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("owner_manager_id", UUID.class),
                        resultSet.getInt("version")
                ))
                .list();
    }

    public boolean clearOwnerAfterProfileDeactivation(
            UUID organizationId,
            UUID previousOwnerManagerId,
            int expectedVersion,
            OffsetDateTime updatedAt
    ) {
        return jdbcClient.sql("""
                UPDATE organizations
                SET owner_manager_id = NULL, version = version + 1, updated_at = :updatedAt
                WHERE id = :organizationId
                  AND owner_manager_id = :previousOwnerManagerId
                  AND version = :expectedVersion
                """)
                .param("organizationId", organizationId)
                .param("previousOwnerManagerId", previousOwnerManagerId)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    private void incrementAccessRevision(UUID profileId) {
        if (profileId == null) {
            return;
        }
        jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET access_revision = access_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :profileId AND role = 'USER'
                """)
                .param("profileId", profileId)
                .update();
    }

    public Optional<Integer> findVersionByOrganizationId(UUID organizationId) {
        return jdbcClient.sql("SELECT version FROM organizations WHERE id = :organizationId")
                .param("organizationId", organizationId)
                .query(Integer.class)
                .optional();
    }

    public Optional<UUID> endOpenDeputy(
            UUID organizationId,
            OffsetDateTime endedAt,
            UUID endedByProfileId,
            String endedByDisplayName
    ) {
        Optional<UUID> deputyProfileId = jdbcClient.sql("""
                SELECT deputy_profile_id
                FROM organization_deputies
                WHERE organization_id = :organizationId AND ended_at IS NULL AND ends_at > :endedAt
                FOR UPDATE
                """)
                .param("organizationId", organizationId)
                .param("endedAt", endedAt)
                .query(UUID.class)
                .optional();
        if (deputyProfileId.isPresent()) {
            jdbcClient.sql("""
                    UPDATE organization_deputies
                    SET ended_at = :endedAt, ended_by_profile_id = :endedByProfileId, ended_by_display_name = :endedByDisplayName
                    WHERE organization_id = :organizationId AND ended_at IS NULL AND ends_at > :endedAt
                    """)
                    .param("organizationId", organizationId)
                    .param("endedAt", endedAt)
                    .param("endedByProfileId", endedByProfileId)
                    .param("endedByDisplayName", endedByDisplayName)
                    .update();
        }
        return deputyProfileId;
    }

    public OrganizationAssignmentEvent insertEvent(
            UUID eventId,
            UUID organizationId,
            UUID commandId,
            UUID previousOwnerManagerId,
            String previousOwnerManagerDisplayName,
            UUID ownerManagerId,
            String newOwnerManagerDisplayName,
            UUID actorProfileId,
            String actorDisplayName,
            String requestId,
            OrganizationAssignmentReason reason,
            String handoverNote,
            int version,
            OffsetDateTime occurredAt
    ) {
        jdbcClient.sql("""
                INSERT INTO organization_assignment_events (
                    id, organization_id, command_id, previous_owner_manager_id, previous_owner_manager_display_name,
                    owner_manager_id, new_owner_manager_display_name, actor_profile_id, actor_display_name,
                    request_id, reason, handover_note, version, occurred_at
                ) VALUES (
                    :id, :organizationId, :commandId, :previousOwnerManagerId, :previousOwnerManagerDisplayName,
                    :ownerManagerId, :newOwnerManagerDisplayName, :actorProfileId, :actorDisplayName,
                    :requestId, :reason, :handoverNote, :version, :occurredAt
                )
                """)
                .param("id", eventId)
                .param("organizationId", organizationId)
                .param("commandId", commandId)
                .param("previousOwnerManagerId", previousOwnerManagerId)
                .param("previousOwnerManagerDisplayName", previousOwnerManagerDisplayName)
                .param("ownerManagerId", ownerManagerId)
                .param("newOwnerManagerDisplayName", newOwnerManagerDisplayName)
                .param("actorProfileId", actorProfileId)
                .param("actorDisplayName", actorDisplayName)
                .param("requestId", requestId)
                .param("reason", reason == null ? null : reason.name())
                .param("handoverNote", handoverNote)
                .param("version", version)
                .param("occurredAt", occurredAt)
                .update();
        return new OrganizationAssignmentEvent(
                eventId,
                organizationId,
                commandId,
                previousOwnerManagerId,
                previousOwnerManagerDisplayName,
                ownerManagerId,
                newOwnerManagerDisplayName,
                actorProfileId,
                actorDisplayName,
                requestId,
                reason,
                handoverNote,
                version,
                occurredAt
        );
    }

    public List<OrganizationAssignmentEvent> findEventsByOrganizationId(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT id, organization_id, command_id, previous_owner_manager_id, previous_owner_manager_display_name,
                       owner_manager_id, new_owner_manager_display_name, actor_profile_id, actor_display_name,
                       request_id, reason, handover_note, version, occurred_at
                FROM organization_assignment_events
                WHERE organization_id = :organizationId
                ORDER BY occurred_at ASC, version ASC, id ASC
                """)
                .param("organizationId", organizationId)
                .query(this::mapEvent)
                .list();
    }

    private OrganizationAssignmentEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        String reason = resultSet.getString("reason");
        return new OrganizationAssignmentEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("command_id", UUID.class),
                resultSet.getObject("previous_owner_manager_id", UUID.class),
                resultSet.getString("previous_owner_manager_display_name"),
                resultSet.getObject("owner_manager_id", UUID.class),
                resultSet.getString("new_owner_manager_display_name"),
                resultSet.getObject("actor_profile_id", UUID.class),
                resultSet.getString("actor_display_name"),
                resultSet.getString("request_id"),
                reason == null ? null : OrganizationAssignmentReason.valueOf(reason),
                resultSet.getString("handover_note"),
                resultSet.getInt("version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
        );
    }
}
