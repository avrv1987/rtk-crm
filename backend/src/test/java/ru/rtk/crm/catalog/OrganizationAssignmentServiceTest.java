package ru.rtk.crm.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:organization_assignment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        OrganizationAssignmentRepository.class,
        CommandIdempotencyRepository.class,
        OrganizationAssignmentService.class,
        OrganizationAssignmentServiceTest.JsonConfiguration.class
})
class OrganizationAssignmentServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LEADER_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID USER_A_NEXT = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID LEADER_B = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID INACTIVE_USER_A = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000016");
    private static final UUID ORGANIZATION_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORGANIZATION_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000999";

    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile userA = new CrmProfile(USER_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile userB = new CrmProfile(USER_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private OrganizationAssignmentService organizationAssignmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        createSchema();
        jdbcTemplate.update("DELETE FROM organization_assignment_events");
        jdbcTemplate.update("DELETE FROM command_idempotency_records");
        jdbcTemplate.update("DELETE FROM organizations");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        insertProfile(LEADER_A, "Елена Руководитель", "LEADER", TEAM_A, true);
        insertProfile(USER_A, "Анна Менеджер", "USER", TEAM_A, true);
        insertProfile(USER_A_NEXT, "Борис Менеджер", "USER", TEAM_A, true);
        insertProfile(USER_B, "Виктор Менеджер", "USER", TEAM_B, true);
        insertProfile(LEADER_B, "Галина Руководитель", "LEADER", TEAM_B, true);
        insertProfile(INACTIVE_USER_A, "Дмитрий Неактивный", "USER", TEAM_A, false);
        insertProfile(ADMIN, "Администратор", "ADMIN", null, true);
        insertOrganization(ORGANIZATION_A, "Университет А", TEAM_A, USER_A);
        insertOrganization(ORGANIZATION_B, "Университет Б", TEAM_B, USER_B);
    }

    @Test
    void leaderGetsOnlyActiveUsersOfTheOrganizationTeamAndSnapshotsNamesInHistory() {
        List<OrganizationAssignmentCandidate> candidates = organizationAssignmentService.options(leaderA, ORGANIZATION_A);

        assertThat(candidates).containsExactly(
                new OrganizationAssignmentCandidate(USER_A, "Анна Менеджер"),
                new OrganizationAssignmentCandidate(USER_A_NEXT, "Борис Менеджер")
        );

        OrganizationAssignmentResult result = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, USER_A_NEXT),
                "assignment-create",
                REQUEST_ID
        );

        assertThat(result.organization().ownerManagerId()).isEqualTo(USER_A_NEXT);
        assertThat(result.organization().version()).isEqualTo(1);
        assertThat(result.event()).satisfies(event -> {
            assertThat(event.previousOwnerManagerId()).isEqualTo(USER_A);
            assertThat(event.previousOwnerManagerDisplayName()).isEqualTo("Анна Менеджер");
            assertThat(event.ownerManagerId()).isEqualTo(USER_A_NEXT);
            assertThat(event.newOwnerManagerDisplayName()).isEqualTo("Борис Менеджер");
            assertThat(event.actorProfileId()).isEqualTo(LEADER_A);
            assertThat(event.actorDisplayName()).isEqualTo("Елена Руководитель");
            assertThat(event.requestId()).isEqualTo(REQUEST_ID);
            assertThat(event.version()).isEqualTo(1);
            assertThat(event.occurredAt()).isNotNull();
        });

        jdbcTemplate.update("UPDATE crm_user_profiles SET display_name = ? WHERE id = ?", "Новое имя", USER_A_NEXT);
        assertThat(organizationAssignmentService.events(leaderA, ORGANIZATION_A)).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo(result.event().id());
            assertThat(event.previousOwnerManagerDisplayName()).isEqualTo("Анна Менеджер");
            assertThat(event.newOwnerManagerDisplayName()).isEqualTo("Борис Менеджер");
            assertThat(event.actorDisplayName()).isEqualTo("Елена Руководитель");
            assertThat(event.requestId()).isEqualTo(REQUEST_ID);
        });
        assertThat(accessRevision(USER_A)).isEqualTo(1);
        assertThat(accessRevision(USER_A_NEXT)).isEqualTo(1);
    }

    @Test
    void userIsForbiddenAfterVisibilityWhileForeignAndAdminScopeAreNotFound() {
        assertThatThrownBy(() -> organizationAssignmentService.options(userA, ORGANIZATION_A))
                .isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> organizationAssignmentService.events(userA, ORGANIZATION_A))
                .isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> organizationAssignmentService.assign(
                userA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, USER_A_NEXT),
                "user-assign",
                REQUEST_ID
        )).isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertThatThrownBy(() -> organizationAssignmentService.options(userB, ORGANIZATION_A))
                .isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> organizationAssignmentService.options(leaderA, ORGANIZATION_B))
                .isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> organizationAssignmentService.options(admin, ORGANIZATION_A))
                .isInstanceOf(OrganizationNotFoundException.class);
    }

    @Test
    void rejectsInactiveNonUserAndCrossTeamCandidatesWithoutChangingTheOrganization() {
        assertRejectedCandidate(USER_B, "cross-team");
        assertRejectedCandidate(LEADER_B, "non-user");
        assertRejectedCandidate(INACTIVE_USER_A, "inactive");

        assertThat(ownerOf(ORGANIZATION_A)).isEqualTo(USER_A);
        assertThat(versionOf(ORGANIZATION_A)).isZero();
        assertThat(count("organization_assignment_events")).isZero();
    }

    @Test
    void replaysTheSameAssignmentAndRejectsAChangedPayloadForTheSameKey() {
        OrganizationAssignmentRequest request = new OrganizationAssignmentRequest(0, USER_A_NEXT);

        OrganizationAssignmentResult assigned = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                request,
                "assignment-replay",
                REQUEST_ID
        );
        OrganizationAssignmentResult replayed = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                request,
                "assignment-replay",
                REQUEST_ID
        );

        assertThat(replayed).isEqualTo(assigned);
        assertThat(count("organization_assignment_events")).isEqualTo(1);
        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, null),
                "assignment-replay",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(exception.currentVersion()).isNull();
        });
    }

    @Test
    void reportsOptimisticVersionConflictAndRecordsExplicitDeassignment() {
        OrganizationAssignmentResult assigned = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, USER_A_NEXT),
                "assignment-version-1",
                REQUEST_ID
        );

        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, null),
                "assignment-stale",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            assertThat(exception.currentVersion()).isEqualTo(1);
        });

        OrganizationAssignmentResult removed = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(assigned.organization().version(), null),
                "assignment-remove",
                REQUEST_ID
        );

        assertThat(removed.organization().ownerManagerId()).isNull();
        assertThat(removed.organization().version()).isEqualTo(2);
        assertThat(removed.event()).satisfies(event -> {
            assertThat(event.previousOwnerManagerId()).isEqualTo(USER_A_NEXT);
            assertThat(event.previousOwnerManagerDisplayName()).isEqualTo("Борис Менеджер");
            assertThat(event.ownerManagerId()).isNull();
            assertThat(event.newOwnerManagerDisplayName()).isNull();
            assertThat(event.version()).isEqualTo(2);
        });
        assertThat(organizationAssignmentService.events(leaderA, ORGANIZATION_A)).hasSize(2);
        assertThat(accessRevision(USER_A_NEXT)).isEqualTo(2);
    }

    @Test
    void requiresOwnerManagerIdPropertyButPermitsAnExplicitNull() throws Exception {
        OrganizationAssignmentRequest missing = objectMapper.readValue(
                "{\"version\":0}",
                OrganizationAssignmentRequest.class
        );
        OrganizationAssignmentRequest explicitNull = objectMapper.readValue(
                "{\"version\":0,\"ownerManagerId\":null}",
                OrganizationAssignmentRequest.class
        );

        assertThat(missing.ownerManagerIdPresent()).isFalse();
        assertThat(explicitNull.ownerManagerIdPresent()).isTrue();
        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                missing,
                "assignment-missing-owner",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("ownerManagerId");
        });
    }

    @Test
    void rejectsNoOpOwnerChangeWithoutWritingAnEventOrChangingVersion() {
        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, USER_A),
                "assignment-noop",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("ownerManagerId");
        });

        assertThat(versionOf(ORGANIZATION_A)).isZero();
        assertThat(count("organization_assignment_events")).isZero();
        assertThat(accessRevision(USER_A)).isZero();
    }

    @Test
    void conditionalUpdateCannotAssignAProfileThatHasBecomeInactive() {
        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", USER_A_NEXT);

        boolean updated = organizationAssignmentRepository().updateOwner(
                ORGANIZATION_A,
                TEAM_A,
                0,
                USER_A_NEXT,
                OffsetDateTime.parse("2026-09-22T12:30:00+00:00")
        );

        assertThat(updated).isFalse();
        assertThat(ownerOf(ORGANIZATION_A)).isEqualTo(USER_A);
        assertThat(versionOf(ORGANIZATION_A)).isZero();
    }

    private void assertRejectedCandidate(UUID candidateId, String idempotencyKey) {
        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, candidateId),
                idempotencyKey,
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("ownerManagerId");
            assertThat(exception).hasMessage("Назначить можно только активного менеджера команды этого вуза");
        });
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY,
                    name VARCHAR(160) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY,
                    display_name VARCHAR(200) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    team_id UUID,
                    active BOOLEAN NOT NULL,
                    access_revision INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY,
                    name VARCHAR(300) NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    team_id UUID NOT NULL,
                    owner_manager_id UUID,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY,
                    actor_profile_id UUID NOT NULL,
                    operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(10000),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    command_id UUID NOT NULL,
                    previous_owner_manager_id UUID,
                    previous_owner_manager_display_name VARCHAR(200),
                    owner_manager_id UUID,
                    new_owner_manager_display_name VARCHAR(200),
                    actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (command_id)
                )
                """);
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId, boolean active) {
        jdbcTemplate.update(
                """
                        INSERT INTO crm_user_profiles (id, display_name, role, team_id, active)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                id,
                displayName,
                role,
                teamId,
                active
        );
    }

    private void insertOrganization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update(
                """
                        INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                name,
                "UNIVERSITY",
                teamId,
                ownerManagerId,
                0,
                OffsetDateTime.parse("2026-09-22T12:00:00+00:00")
        );
    }

    private UUID ownerOf(UUID organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_manager_id FROM organizations WHERE id = ?",
                UUID.class,
                organizationId
        );
    }

    private int versionOf(UUID organizationId) {
        return jdbcTemplate.queryForObject("SELECT version FROM organizations WHERE id = ?", Integer.class, organizationId);
    }

    private int accessRevision(UUID profileId) {
        return jdbcTemplate.queryForObject(
                "SELECT access_revision FROM crm_user_profiles WHERE id = ?",
                Integer.class,
                profileId
        );
    }

    @Autowired
    private OrganizationAssignmentRepository organizationAssignmentRepository;

    private OrganizationAssignmentRepository organizationAssignmentRepository() {
        return organizationAssignmentRepository;
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfiguration {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
