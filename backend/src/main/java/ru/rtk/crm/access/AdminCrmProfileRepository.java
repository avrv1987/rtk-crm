package ru.rtk.crm.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            profile.login, profile.activation_requested_at, profile.idp_enabled, profile.role_synced_at,
            profile.role_reconcile_required, profile.last_login_at,
            (SELECT partner_organization.name FROM organizations partner_organization
             WHERE partner_organization.id = profile.partner_organization_id) AS partner_organization_name
            """;

    private final JdbcClient jdbcClient;

    public AdminCrmProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AdminCrmProfilePage findPage(AdminCrmProfileQuery query) {
        Filter filter = filter(query);
        List<AdminCrmProfile> items = bind(jdbcClient.sql("""
                SELECT %s
                FROM crm_user_profiles profile
                LEFT JOIN teams team ON team.id = profile.team_id
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(PROFILE_COLUMNS, filter.condition(), query.sort().orderBy())), filter)
                .param("size", query.size())
                .param("offset", query.offset())
                .query(this::mapProfile)
                .list();
        long total = bind(jdbcClient.sql("SELECT COUNT(*) FROM crm_user_profiles profile WHERE " + filter.condition()), filter)
                .query(Long.class)
                .single();
        long pendingTotal = jdbcClient.sql("SELECT COUNT(*) FROM crm_user_profiles WHERE pending_activation = TRUE")
                .query(Long.class)
                .single();
        return new AdminCrmProfilePage(items, query.page(), query.size(), total, pendingTotal);
    }

    public List<AdminCrmProfile> findRows(AdminCrmProfileQuery query, int limit) {
        Filter filter = filter(query);
        return bind(jdbcClient.sql("""
                SELECT %s
                FROM crm_user_profiles profile
                LEFT JOIN teams team ON team.id = profile.team_id
                WHERE %s
                ORDER BY %s
                LIMIT :limit
                """.formatted(PROFILE_COLUMNS, filter.condition(), query.sort().orderBy())), filter)
                .param("limit", limit)
                .query(this::mapProfile)
                .list();
    }

    private Filter filter(AdminCrmProfileQuery query) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        conditions.add("TRUE");
        if (query.status() != null) {
            conditions.add(switch (query.status()) {
                case ACTIVE -> "profile.active = TRUE";
                case PENDING -> "profile.pending_activation = TRUE";
                case BLOCKED -> "profile.active = FALSE AND profile.pending_activation = FALSE";
                case SYNC_REQUIRED -> "(profile.role_reconcile_required = TRUE"
                        + " OR profile.pending_activation = FALSE AND profile.active <> profile.idp_enabled)";
            });
        }
        if (query.role() != null) {
            conditions.add("profile.role = :role");
            params.put("role", query.role().name());
        }
        if (query.teamId() != null) {
            conditions.add("profile.team_id = :teamId");
            params.put("teamId", query.teamId());
        }
        if (query.search() != null) {
            conditions.add("(LOWER(profile.display_name) LIKE :search " + SearchPattern.LIKE_ESCAPE
                    + " OR LOWER(COALESCE(profile.login, '')) LIKE :search " + SearchPattern.LIKE_ESCAPE + ")");
            params.put("search", SearchPattern.contains(query.search()));
        }
        return new Filter(String.join(" AND ", conditions), params);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Filter filter) {
        filter.params().forEach(statement::param);
        return statement;
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

    // Блокировка в порядке id: единый порядок захвата строк исключает взаимные блокировки (см. validate).
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

    // Профиль, который из-за смены роли стал неактивным (КАМ или руководитель без команды), снова попадает в
    // очередь активации: администратор видит его и назначает команду.
    public boolean applyKeycloakRole(UUID profileId, int expectedVersion, ProfileState state, OffsetDateTime updatedAt) {
        return jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET role = :role,
                    active = :active,
                    enrolment_operator = :enrolmentOperator,
                    pending_activation = CASE WHEN active AND NOT :active THEN TRUE ELSE pending_activation END,
                    access_revision = access_revision + 1,
                    role_synced_at = :updatedAt,
                    version = version + 1,
                    updated_at = :updatedAt
                WHERE id = :profileId AND version = :expectedVersion
                """)
                .param("profileId", profileId)
                .param("expectedVersion", expectedVersion)
                .param("role", state.role().name())
                .param("active", state.active())
                .param("enrolmentOperator", state.enrolmentOperator())
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public void markRoleSynced(UUID profileId, OffsetDateTime syncedAt) {
        jdbcClient.sql("UPDATE crm_user_profiles SET role_synced_at = :syncedAt WHERE id = :profileId")
                .param("profileId", profileId)
                .param("syncedAt", syncedAt)
                .update();
    }

    public void setRoleReconcileRequired(UUID profileId, boolean required) {
        jdbcClient.sql("UPDATE crm_user_profiles SET role_reconcile_required = :required WHERE id = :profileId")
                .param("profileId", profileId)
                .param("required", required)
                .update();
    }

    public void recordLogin(UUID profileId, OffsetDateTime loggedInAt) {
        jdbcClient.sql("UPDATE crm_user_profiles SET last_login_at = :loggedInAt WHERE id = :profileId")
                .param("profileId", profileId)
                .param("loggedInAt", loggedInAt)
                .update();
    }

    public Optional<UUID> findIdByIdentity(String issuer, String subject) {
        return jdbcClient.sql("""
                SELECT id FROM crm_user_profiles
                WHERE issuer = :issuer AND subject = :subject AND anonymized_at IS NULL
                """)
                .param("issuer", issuer)
                .param("subject", subject)
                .query(UUID.class)
                .optional();
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
                resultSet.getString("partner_organization_name"),
                resultSet.getObject("role_synced_at", OffsetDateTime.class),
                resultSet.getBoolean("role_reconcile_required"),
                resultSet.getObject("last_login_at", OffsetDateTime.class)
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

    private record Filter(String condition, Map<String, Object> params) {
    }
}
