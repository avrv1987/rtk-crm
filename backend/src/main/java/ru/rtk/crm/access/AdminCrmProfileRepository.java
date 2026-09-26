package ru.rtk.crm.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminCrmProfileRepository {
    private static final String PROFILE_COLUMNS = """
            profile.id, profile.display_name, profile.role, profile.team_id, team.name AS team_name,
            profile.active, profile.pending_activation, profile.access_revision, profile.version
            """;

    private final JdbcClient jdbcClient;

    public AdminCrmProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AdminCrmProfilePage findPage(AdminCrmProfileQuery query) {
        String condition = query.pendingOnly() ? "profile.pending_activation = TRUE" : "TRUE";
        List<AdminCrmProfile> items = jdbcClient.sql("""
                SELECT %s
                FROM crm_user_profiles profile
                LEFT JOIN teams team ON team.id = profile.team_id
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(PROFILE_COLUMNS, condition, query.sort().orderBy()))
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapProfile)
                .list();
        long total = jdbcClient.sql("SELECT COUNT(*) FROM crm_user_profiles profile WHERE " + condition)
                .query(Long.class)
                .single();
        return new AdminCrmProfilePage(items, query.page(), query.size(), total);
    }

    public Optional<AdminCrmProfile> findByIdForUpdate(UUID profileId) {
        return jdbcClient.sql("SELECT id FROM crm_user_profiles WHERE id = :profileId FOR UPDATE")
                .param("profileId", profileId)
                .query(UUID.class)
                .optional()
                .flatMap(this::findById);
    }

    public Optional<AdminCrmProfile> findById(UUID profileId) {
        return jdbcClient.sql("""
                SELECT %s
                FROM crm_user_profiles profile
                LEFT JOIN teams team ON team.id = profile.team_id
                WHERE profile.id = :profileId
                """.formatted(PROFILE_COLUMNS))
                .param("profileId", profileId)
                .query(this::mapProfile)
                .optional();
    }

    public List<UUID> lockActiveAdministrators() {
        return jdbcClient.sql("""
                SELECT id
                FROM crm_user_profiles
                WHERE role = 'ADMIN' AND active = TRUE
                ORDER BY id
                FOR UPDATE
                """)
                .query(UUID.class)
                .list();
    }

    public boolean update(
            UUID profileId,
            int expectedVersion,
            ProfileState state,
            boolean accessChanged,
            OffsetDateTime updatedAt
    ) {
        return jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET display_name = :displayName,
                    role = :role,
                    team_id = :teamId,
                    active = :active,
                    pending_activation = CASE WHEN :active THEN FALSE ELSE pending_activation END,
                    access_revision = access_revision + :revisionIncrement,
                    version = version + 1,
                    updated_at = :updatedAt
                WHERE id = :profileId AND version = :expectedVersion
                """)
                .param("profileId", profileId)
                .param("expectedVersion", expectedVersion)
                .param("displayName", state.displayName())
                .param("role", state.role().name())
                .param("teamId", state.teamId())
                .param("active", state.active())
                .param("revisionIncrement", accessChanged ? 1 : 0)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public Optional<String> findDisplayName(UUID profileId) {
        return jdbcClient.sql("SELECT display_name FROM crm_user_profiles WHERE id = :profileId")
                .param("profileId", profileId)
                .query(String.class)
                .optional();
    }

    public void insertEvent(
            UUID eventId,
            UUID profileId,
            UUID commandId,
            UUID actorProfileId,
            String actorDisplayName,
            ProfileState previous,
            ProfileState current,
            String requestId,
            int version,
            OffsetDateTime occurredAt
    ) {
        jdbcClient.sql("""
                INSERT INTO crm_profile_events (
                    id, profile_id, command_id, actor_profile_id, actor_display_name,
                    previous_display_name, display_name, previous_role, role, previous_team_id, team_id,
                    previous_active, active, request_id, version, occurred_at
                ) VALUES (
                    :id, :profileId, :commandId, :actorProfileId, :actorDisplayName,
                    :previousDisplayName, :displayName, :previousRole, :role, :previousTeamId, :teamId,
                    :previousActive, :active, :requestId, :version, :occurredAt
                )
                """)
                .param("id", eventId)
                .param("profileId", profileId)
                .param("commandId", commandId)
                .param("actorProfileId", actorProfileId)
                .param("actorDisplayName", actorDisplayName)
                .param("previousDisplayName", previous.displayName())
                .param("displayName", current.displayName())
                .param("previousRole", previous.role().name())
                .param("role", current.role().name())
                .param("previousTeamId", previous.teamId())
                .param("teamId", current.teamId())
                .param("previousActive", previous.active())
                .param("active", current.active())
                .param("requestId", requestId)
                .param("version", version)
                .param("occurredAt", occurredAt)
                .update();
    }

    public List<CrmProfileEvent> findEventsByProfileId(UUID profileId) {
        return jdbcClient.sql("""
                SELECT event.id, event.profile_id, event.command_id, event.actor_profile_id, event.actor_display_name,
                       event.previous_display_name, event.display_name, event.previous_role, event.role,
                       event.previous_team_id, previous_team.name AS previous_team_name,
                       event.team_id, team.name AS team_name,
                       event.previous_active, event.active, event.request_id, event.version, event.occurred_at
                FROM crm_profile_events event
                LEFT JOIN teams previous_team ON previous_team.id = event.previous_team_id
                LEFT JOIN teams team ON team.id = event.team_id
                WHERE event.profile_id = :profileId
                ORDER BY event.occurred_at DESC, event.id DESC
                """)
                .param("profileId", profileId)
                .query(this::mapEvent)
                .list();
    }

    private AdminCrmProfile mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AdminCrmProfile(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("display_name"),
                UserRole.valueOf(resultSet.getString("role")),
                resultSet.getObject("team_id", UUID.class),
                resultSet.getString("team_name"),
                resultSet.getBoolean("active"),
                resultSet.getBoolean("pending_activation"),
                resultSet.getInt("access_revision"),
                resultSet.getInt("version")
        );
    }

    private CrmProfileEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CrmProfileEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getObject("command_id", UUID.class),
                resultSet.getObject("actor_profile_id", UUID.class),
                resultSet.getString("actor_display_name"),
                resultSet.getString("previous_display_name"),
                resultSet.getString("display_name"),
                UserRole.valueOf(resultSet.getString("previous_role")),
                UserRole.valueOf(resultSet.getString("role")),
                resultSet.getObject("previous_team_id", UUID.class),
                resultSet.getString("previous_team_name"),
                resultSet.getObject("team_id", UUID.class),
                resultSet.getString("team_name"),
                resultSet.getBoolean("previous_active"),
                resultSet.getBoolean("active"),
                resultSet.getString("request_id"),
                resultSet.getInt("version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
        );
    }

    public record ProfileState(String displayName, UserRole role, UUID teamId, boolean active) {
        static ProfileState of(AdminCrmProfile profile) {
            return new ProfileState(profile.displayName(), profile.role(), profile.teamId(), profile.active());
        }
    }
}
