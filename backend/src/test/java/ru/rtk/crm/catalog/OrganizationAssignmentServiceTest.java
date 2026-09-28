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
    private static final UUID SECOND_LEADER_A = UUID.fromString("00000000-0000-0000-0000-000000000017");
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
    private OrganizationRepository organizationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        createSchema();
        jdbcTemplate.update("DELETE FROM contacts");
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
    void leaderGetsActiveUsersOfTheOrganizationTeamAndHimselfAndSnapshotsNamesInHistory() {
        insertProfile(SECOND_LEADER_A, "Жанна Руководитель", "LEADER", TEAM_A, true);
        List<OrganizationAssignmentCandidate> candidates = organizationAssignmentService.options(leaderA, ORGANIZATION_A);

        assertThat(candidates).containsExactly(
                new OrganizationAssignmentCandidate(USER_A, "Анна Менеджер"),
                new OrganizationAssignmentCandidate(USER_A_NEXT, "Борис Менеджер"),
                new OrganizationAssignmentCandidate(LEADER_A, "Елена Руководитель")
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
    void transferredOrganizationStaysInheritedUntilTheCurrentOwnerConfirmsAContact() {
        CrmProfile userANext = new CrmProfile(USER_A_NEXT, UserRole.USER, TEAM_A, 0);
        OffsetDateTime beforeTransfer = OffsetDateTime.now().minusDays(1);
        insertConfirmedContact(ORGANIZATION_A, USER_A, beforeTransfer);
        assertThat(organizationRepository.findVisibleById(userA, ORGANIZATION_A)).get()
                .extracting(Organization::inherited).isEqualTo(false);

        organizationAssignmentService.assign(
                leaderA, ORGANIZATION_A, new OrganizationAssignmentRequest(0, USER_A_NEXT), "inherit-transfer", REQUEST_ID
        );

        assertThat(organizationRepository.findVisibleById(userANext, ORGANIZATION_A)).get()
                .extracting(Organization::inherited).isEqualTo(true);
        assertThat(organizationRepository.findVisibleById(leaderA, ORGANIZATION_A)).get()
                .extracting(Organization::inherited).isEqualTo(true);
        insertConfirmedContact(ORGANIZATION_A, USER_A, OffsetDateTime.now().plusMinutes(1));
        assertThat(organizationRepository.findVisibleById(leaderA, ORGANIZATION_A)).get()
                .extracting(Organization::inherited).isEqualTo(true);

        insertConfirmedContact(ORGANIZATION_A, USER_A_NEXT, OffsetDateTime.now().plusMinutes(1));
        assertThat(organizationRepository.findVisibleById(leaderA, ORGANIZATION_A)).get()
                .extracting(Organization::inherited).isEqualTo(false);
        assertThat(organizationRepository.findVisibleById(userB, ORGANIZATION_B)).get()
                .extracting(Organization::inherited).isEqualTo(false);

        jdbcTemplate.update("DELETE FROM contacts WHERE confirmed_by = ? AND confirmed_at > ?", USER_A, beforeTransfer);
        organizationAssignmentService.assign(
                leaderA, ORGANIZATION_A, new OrganizationAssignmentRequest(1, USER_A), "inherit-return", REQUEST_ID
        );
        assertThat(organizationRepository.findVisibleById(userA, ORGANIZATION_A)).get()
                .extracting(Organization::inherited).isEqualTo(true);
    }

    @Test
    void userIsForbiddenAfterVisibilityWhileForeignAndAdminScopeAreNotFound() {
        assertThatThrownBy(() -> organizationAssignmentService.options(userA, ORGANIZATION_A))
                .isInstanceOf(OrganizationAssignmentAccessDeniedException.class);
        assertThat(organizationAssignmentService.events(userA, ORGANIZATION_A)).isEmpty();
        assertThatThrownBy(() -> organizationAssignmentService.events(userB, ORGANIZATION_A))
                .isInstanceOf(OrganizationNotFoundException.class);
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
    void handoverNoteTravelsWithTheTransferAndOnlyTheNewManagerSeesTheHistory() {
        OrganizationAssignmentRequest request = new OrganizationAssignmentRequest(0, USER_A_NEXT);
        request.setHandoverNote("  Ждут проект договора до 10.10  ");

        OrganizationAssignmentResult result = organizationAssignmentService.assign(
                leaderA, ORGANIZATION_A, request, "handover", REQUEST_ID
        );
        OrganizationAssignmentResult replay = organizationAssignmentService.assign(
                leaderA, ORGANIZATION_A, request, "handover", REQUEST_ID
        );

        assertThat(result.event().handoverNote()).isEqualTo("Ждут проект договора до 10.10");
        assertThat(replay.event().id()).isEqualTo(result.event().id());
        assertThat(count("organization_assignment_events")).isEqualTo(1);
        CrmProfile newOwner = new CrmProfile(USER_A_NEXT, UserRole.USER, TEAM_A, 0);
        assertThat(organizationAssignmentService.events(newOwner, ORGANIZATION_A)).singleElement().satisfies(event -> {
            assertThat(event.handoverNote()).isEqualTo("Ждут проект договора до 10.10");
            assertThat(event.previousOwnerManagerDisplayName()).isEqualTo("Анна Менеджер");
            assertThat(event.actorDisplayName()).isEqualTo("Елена Руководитель");
            assertThat(event.reason()).isNull();
        });
        assertThatThrownBy(() -> organizationAssignmentService.events(userA, ORGANIZATION_A))
                .isInstanceOf(OrganizationNotFoundException.class);

        OrganizationAssignmentRequest changedNote = new OrganizationAssignmentRequest(0, USER_A_NEXT);
        changedNote.setHandoverNote("Другая записка");
        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA, ORGANIZATION_A, changedNote, "handover", REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class,
                exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void handedBackOrganizationShowsBothChangesAndTheNoteToTheReturningManagerOnly() {
        OrganizationAssignmentRequest handover = new OrganizationAssignmentRequest(0, USER_A_NEXT);
        handover.setHandoverNote("Ждут проект договора до 10.10");
        organizationAssignmentService.assign(leaderA, ORGANIZATION_A, handover, "handover", REQUEST_ID);
        organizationAssignmentService.assign(
                leaderA, ORGANIZATION_A, new OrganizationAssignmentRequest(1, USER_A), "hand-back", REQUEST_ID
        );

        assertThat(organizationAssignmentService.events(userA, ORGANIZATION_A)).satisfiesExactly(
                first -> {
                    assertThat(first.newOwnerManagerDisplayName()).isEqualTo("Борис Менеджер");
                    assertThat(first.handoverNote()).isEqualTo("Ждут проект договора до 10.10");
                },
                second -> {
                    assertThat(second.previousOwnerManagerDisplayName()).isEqualTo("Борис Менеджер");
                    assertThat(second.newOwnerManagerDisplayName()).isEqualTo("Анна Менеджер");
                    assertThat(second.handoverNote()).isNull();
                }
        );
        CrmProfile formerOwner = new CrmProfile(USER_A_NEXT, UserRole.USER, TEAM_A, 0);
        assertThatThrownBy(() -> organizationAssignmentService.events(formerOwner, ORGANIZATION_A))
                .isInstanceOf(OrganizationNotFoundException.class);
    }

    @Test
    void rejectsInactiveNonUserAndCrossTeamCandidatesWithoutChangingTheOrganization() {
        assertRejectedCandidate(USER_B, "cross-team");
        assertRejectedCandidate(LEADER_B, "non-user");
        assertRejectedCandidate(INACTIVE_USER_A, "inactive");
        assertRejectedCandidate(ADMIN, "admin");
        insertProfile(SECOND_LEADER_A, "Жанна Руководитель", "LEADER", TEAM_A, true);
        assertRejectedCandidate(SECOND_LEADER_A, "other-leader");

        assertThat(ownerOf(ORGANIZATION_A)).isEqualTo(USER_A);
        assertThat(versionOf(ORGANIZATION_A)).isZero();
        assertThat(count("organization_assignment_events")).isZero();
    }

    @Test
    void leaderAssignsHimselfWithAuditReplayAndHandsTheOrganizationBack() {
        OrganizationAssignmentRequest request = new OrganizationAssignmentRequest(0, LEADER_A);

        OrganizationAssignmentResult assigned = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                request,
                "assign-leader-self",
                REQUEST_ID
        );
        OrganizationAssignmentResult replayed = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                request,
                "assign-leader-self",
                REQUEST_ID
        );

        assertThat(replayed).isEqualTo(assigned);
        assertThat(assigned.organization().ownerManagerId()).isEqualTo(LEADER_A);
        assertThat(assigned.organization().ownerManagerName()).isEqualTo("Елена Руководитель");
        assertThat(assigned.organization().requiresAssignment()).isFalse();
        assertThat(assigned.organization().version()).isEqualTo(1);
        assertThat(assigned.event()).satisfies(event -> {
            assertThat(event.previousOwnerManagerId()).isEqualTo(USER_A);
            assertThat(event.ownerManagerId()).isEqualTo(LEADER_A);
            assertThat(event.newOwnerManagerDisplayName()).isEqualTo("Елена Руководитель");
            assertThat(event.actorProfileId()).isEqualTo(LEADER_A);
        });
        assertThat(count("organization_assignment_events")).isEqualTo(1);
        assertThat(organizationRepository.findVisible(leaderA, OrganizationQuery.from(0, 25, "name,asc", null, true)).items())
                .extracting(Organization::id)
                .doesNotContain(ORGANIZATION_A);
        assertThat(organizationRepository.findVisibleById(userA, ORGANIZATION_A)).isEmpty();

        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(0, USER_A),
                "assign-after-leader-stale",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                assertThat(exception.code()).isEqualTo("VERSION_CONFLICT"));
        OrganizationAssignmentResult handedBack = organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_A,
                new OrganizationAssignmentRequest(1, USER_A),
                "assign-after-leader",
                REQUEST_ID
        );

        assertThat(handedBack.event().previousOwnerManagerId()).isEqualTo(LEADER_A);
        assertThat(handedBack.event().previousOwnerManagerDisplayName()).isEqualTo("Елена Руководитель");
        assertThat(ownerOf(ORGANIZATION_A)).isEqualTo(USER_A);
        assertThat(accessRevision(LEADER_A)).isZero();
        assertThat(accessRevision(USER_A)).isEqualTo(2);
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
            assertThat(exception).hasMessage("Выберите нового ответственного или явно снимите назначение");
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
                LEADER_A,
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
                    name VARCHAR(160) NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL, default_workflow_template_id UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY,
                    login VARCHAR(200),
                    idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE,
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
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE,
                    confirmed_by UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    command_id UUID NOT NULL, actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, ended_at TIMESTAMP WITH TIME ZONE,
                    ended_by_profile_id UUID, ended_by_display_name VARCHAR(200)
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
                    reason VARCHAR(32), handover_note VARCHAR(2000),
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

    private void insertConfirmedContact(UUID organizationId, UUID confirmedBy, OffsetDateTime confirmedAt) {
        jdbcTemplate.update(
                "INSERT INTO contacts (id, organization_id, name, confirmed_at, confirmed_by) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                organizationId,
                "Контакт вуза",
                confirmedAt,
                confirmedBy
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
