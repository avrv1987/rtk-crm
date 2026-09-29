package ru.rtk.crm.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.catalog.SearchPattern;

@Repository
public class AdminCrmProfileRepository {
    private static final String PROFILE_COLUMNS = """
            profile.id, profile.display_name, profile.role, profile.team_id, team.name AS team_name,
            profile.active, profile.enrolment_operator, profile.pending_activation, profile.access_revision, profile.version,
            profile.login, profile.activation_requested_at, profile.idp_enabled,
            (SELECT partner_organization.name FROM organizations partner_organization
             WHERE partner_organization.id = profile.partner_organization_id) AS partner_organization_name
            """;

    private final JdbcClient jdbcClient;

    public AdminCrmProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AdminCrmProfilePage findPage(AdminCrmProfileQuery query) {
        String condition = (query.pendingOnly() ? "profile.pending_activation = TRUE" : "TRUE")
                + (query.search() == null ? "" : " AND (LOWER(profile.display_name) LIKE :search " + SearchPattern.LIKE_ESCAPE
                + " OR LOWER(COALESCE(profile.login, '')) LIKE :search " + SearchPattern.LIKE_ESCAPE + ")");
        String search = query.search() == null ? null : SearchPattern.contains(query.search());
        JdbcClient.StatementSpec itemsQuery = jdbcClient.sql("""
                SELECT %s
                FROM crm_user_profiles profile
                LEFT JOIN teams team ON team.id = profile.team_id
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(PROFILE_COLUMNS, condition, query.sort().orderBy()))
                .param("size", query.size())
                .param("offset", query.offset());
        JdbcClient.StatementSpec totalQuery = jdbcClient.sql("SELECT COUNT(*) FROM crm_user_profiles profile WHERE " + condition);
        if (search != null) {
            itemsQuery.param("search", search);
            totalQuery.param("search", search);
        }
        List<AdminCrmProfile> items = itemsQuery.query(this::mapProfile).list();
        long total = totalQuery.query(Long.class).single();
        long pendingTotal = jdbcClient.sql("SELECT COUNT(*) FROM crm_user_profiles WHERE pending_activation = TRUE")
                .query(Long.class)
                .single();
        return new AdminCrmProfilePage(items, query.page(), query.size(), total, pendingTotal);
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
                    enrolment_operator = :enrolmentOperator,
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
                .param("enrolmentOperator", state.enrolmentOperator())
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

    public Optional<String> findIssuer(UUID profileId) {
        return jdbcClient.sql("SELECT issuer FROM crm_user_profiles WHERE id = :profileId")
                .param("profileId", profileId)
                .query(String.class)
                .optional();
    }

    public void insertEmployee(
            UUID profileId,
            String issuer,
            String subject,
            String displayName,
            String login,
            UserRole role,
            UUID teamId,
            OffsetDateTime now
    ) {
        jdbcClient.sql("""
                INSERT INTO crm_user_profiles (
                    id, issuer, subject, display_name, login, role, team_id, active, pending_activation, idp_enabled, updated_at
                ) VALUES (
                    :id, :issuer, :subject, :displayName, :login, :role, :teamId, TRUE, FALSE, TRUE, :now
                )
                """)
                .param("id", profileId)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("displayName", displayName)
                .param("login", login)
                .param("role", role.name())
                .param("teamId", teamId)
                .param("now", now)
                .update();
    }

    public int deleteSessions(String login) {
        return jdbcClient.sql("DELETE FROM spring_session WHERE principal_name = :login")
                .param("login", login)
                .update();
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
                    previous_active, active, previous_enrolment_operator, enrolment_operator, request_id, version, occurred_at
                ) VALUES (
                    :id, :profileId, :commandId, :actorProfileId, :actorDisplayName,
                    :previousDisplayName, :displayName, :previousRole, :role, :previousTeamId, :teamId,
                    :previousActive, :active, :previousEnrolmentOperator, :enrolmentOperator, :requestId, :version, :occurredAt
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
                .param("previousEnrolmentOperator", previous.enrolmentOperator())
                .param("enrolmentOperator", current.enrolmentOperator())
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
                       event.previous_active, event.active, event.previous_enrolment_operator, event.enrolment_operator,
                       event.request_id, event.version, event.occurred_at
                FROM crm_profile_events event
                LEFT JOIN teams previous_team ON previous_team.id = event.previous_team_id
                LEFT JOIN teams team ON team.id = event.team_id
                WHERE event.profile_id = :profileId
                ORDER BY event.occurred_at DESC, event.version DESC, event.id DESC
                """)
                .param("profileId", profileId)
                .query(this::mapEvent)
                .list();
    }

    private AdminCrmProfile mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        boolean active = resultSet.getBoolean("active");
        boolean pendingActivation = resultSet.getBoolean("pending_activation");
        return new AdminCrmProfile(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("display_name"),
                UserRole.valueOf(resultSet.getString("role")),
                resultSet.getObject("team_id", UUID.class),
                resultSet.getString("team_name"),
                active,
                resultSet.getBoolean("enrolment_operator"),
                pendingActivation,
                resultSet.getInt("access_revision"),
                resultSet.getInt("version"),
                resultSet.getString("login"),
                resultSet.getObject("activation_requested_at", OffsetDateTime.class),
                !pendingActivation && active != resultSet.getBoolean("idp_enabled"),
                null,
                resultSet.getString("partner_organization_name")
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
                resultSet.getBoolean("previous_enrolment_operator"),
                resultSet.getBoolean("enrolment_operator"),
                resultSet.getString("request_id"),
                resultSet.getInt("version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
        );
    }

    public record ProfileState(String displayName, UserRole role, UUID teamId, boolean active, boolean enrolmentOperator) {
        static ProfileState of(AdminCrmProfile profile) {
            return new ProfileState(
                    profile.displayName(), profile.role(), profile.teamId(), profile.active(), profile.enrolmentOperator()
            );
        }

        boolean privileged() {
            return role == UserRole.ADMIN || enrolmentOperator;
        }
    }
}
