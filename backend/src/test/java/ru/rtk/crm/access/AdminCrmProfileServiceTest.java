package ru.rtk.crm.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.AdminOrganization;
import ru.rtk.crm.catalog.AdminOrganizationRepository;
import ru.rtk.crm.catalog.AdminOrganizationService;
import ru.rtk.crm.catalog.AdminOrganizationTeamRequest;
import ru.rtk.crm.catalog.CatalogChangeEventRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationAssignmentEvent;
import ru.rtk.crm.catalog.OrganizationAssignmentReason;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.catalog.OrganizationAssignmentRequest;
import ru.rtk.crm.catalog.OrganizationAssignmentService;
import ru.rtk.crm.catalog.OrganizationQuery;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.CrmProfileRegistrationSuccessHandler;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:admin_crm_profiles;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AdminCrmProfileRepository.class,
        AdminCrmProfileService.class,
        AdminTeamRepository.class,
        AdminTeamService.class,
        CatalogChangeEventRepository.class,
        AdminOrganizationRepository.class,
        AdminOrganizationService.class,
        CurrentProfileService.class,
        UserProfileRepository.class,
        OrganizationRepository.class,
        OrganizationAssignmentRepository.class,
        OrganizationAssignmentService.class,
        CommandIdempotencyRepository.class,
        AuditJournalRepository.class,
        AdminCrmProfileServiceTest.JsonConfiguration.class
})
class AdminCrmProfileServiceTest {
    private static final UUID TEAM_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID MISSING_TEAM = UUID.fromString("10000000-0000-0000-0000-000000000099");
    private static final UUID ADMIN = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID USER_A = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID USER_B = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID LEADER_B = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_ADMIN = UUID.fromString("20000000-0000-0000-0000-000000000006");
    private static final UUID ORGANIZATION_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_A_SECOND = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ORGANIZATION_B = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ORGANIZATION_UNASSIGNED = UUID.fromString("30000000-0000-0000-0000-000000000004");
    private static final String REQUEST_ID = "admin-profile-test-request";
    private static final String ISSUER = "https://issuer.example";

    private final CrmProfile administrator = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile userA = new CrmProfile(USER_A, UserRole.USER, TEAM_A, 0);

    @Autowired
    private AdminCrmProfileService adminCrmProfileService;

    @Autowired
    private AdminCrmProfileRepository adminCrmProfileRepository;

    @Autowired
    private AdminTeamService adminTeamService;

    @Autowired
    private AdminOrganizationService adminOrganizationService;

    @Autowired
    private CurrentProfileService currentProfileService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationAssignmentRepository organizationAssignmentRepository;

    @Autowired
    private OrganizationAssignmentService organizationAssignmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        createSchema();
        jdbcTemplate.update("DELETE FROM audit_events");
        jdbcTemplate.update("DELETE FROM catalog_change_events");
        jdbcTemplate.update("DELETE FROM organization_team_events");
        jdbcTemplate.update("DELETE FROM crm_profile_events");
        jdbcTemplate.update("DELETE FROM organization_assignment_events");
        jdbcTemplate.update("DELETE FROM command_idempotency_records");
        jdbcTemplate.update("DELETE FROM organizations");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        jdbcTemplate.update("DELETE FROM teams");
        insertTeam(TEAM_A, "Команда А");
        insertTeam(TEAM_B, "Команда Б");
        insertProfile(ADMIN, "Администратор", "ADMIN", null, true);
        insertProfile(LEADER_A, "Елена Руководитель", "LEADER", TEAM_A, true);
        insertProfile(USER_A, "Анна Менеджер", "USER", TEAM_A, true);
        insertProfile(USER_B, "Борис Менеджер", "USER", TEAM_B, true);
        insertOrganization(ORGANIZATION_A, "Университет А", TEAM_A, USER_A);
        insertOrganization(ORGANIZATION_A_SECOND, "Университет А-2", TEAM_A, USER_A);
        insertOrganization(ORGANIZATION_B, "Университет Б", TEAM_B, USER_B);
        insertOrganization(ORGANIZATION_UNASSIGNED, "Университет без КАМ", TEAM_A, null);
    }

    @Test
    void administratorsListPaginatedProfilesAndOtherRolesAreForbidden() {
        AdminCrmProfilePage page = adminCrmProfileService.list(
                administrator,
                AdminCrmProfileQuery.from(0, 2, "displayName,asc", false, null)
        );

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allSatisfy(profile -> {
            assertThat(profile.id()).isNotNull();
            assertThat(profile.displayName()).isNotBlank();
            assertThat(profile.role()).isNotNull();
            assertThat(profile.accessRevision()).isZero();
            assertThat(profile.version()).isZero();
        });
        assertThat(profile(USER_A).teamName()).isEqualTo("Команда А");
        assertThatThrownBy(() -> adminCrmProfileService.list(
                leaderA,
                AdminCrmProfileQuery.from(0, 25, "displayName,asc", false, null)
        )).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> adminCrmProfileService.update(
                userA,
                USER_B,
                AdminCrmProfileUpdateRequest.active(0, false),
                "user-forbidden",
                REQUEST_ID
        )).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> adminTeamService.list(leaderA)).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> adminOrganizationService.list(leaderA, organizationQuery()))
                .isInstanceOf(AdminCrmProfileAccessDeniedException.class);
    }

    @Test
    void administratorCannotChangeOwnRoleTeamOrActivityButMayRenameThemselves() {
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                ADMIN,
                AdminCrmProfileUpdateRequest.active(0, false),
                "self-deactivation",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("active"));
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                ADMIN,
                new AdminCrmProfileUpdateRequest(0).setRole(UserRole.LEADER),
                "self-role",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("role"));
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                ADMIN,
                new AdminCrmProfileUpdateRequest(0).setTeamId(TEAM_A),
                "self-team",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("teamId"));

        assertThat(profile(ADMIN).active()).isTrue();
        assertThat(profile(ADMIN).version()).isZero();
        assertThat(count("crm_profile_events")).isZero();
        assertThat(count("command_idempotency_records")).isZero();

        AdminCrmProfile renamed = adminCrmProfileService.update(
                administrator,
                ADMIN,
                new AdminCrmProfileUpdateRequest(0).setDisplayName("  Главный администратор "),
                "self-rename",
                REQUEST_ID
        );
        assertThat(renamed.displayName()).isEqualTo("Главный администратор");
        assertThat(renamed.accessRevision()).isZero();
        assertThat(renamed.version()).isEqualTo(1);
    }

    @Test
    void parsesOptionalProfileFieldsAndRejectsUnknownOnes() throws Exception {
        AdminCrmProfileUpdateRequest request = objectMapper.readValue(
                "{\"version\":0,\"active\":false,\"role\":\"LEADER\",\"teamId\":null,\"displayName\":\"Имя\"}",
                AdminCrmProfileUpdateRequest.class
        );
        AdminCrmProfileUpdateRequest withoutTeam = objectMapper.readValue(
                "{\"version\":2,\"role\":\"USER\"}",
                AdminCrmProfileUpdateRequest.class
        );

        assertThat(request.version()).isZero();
        assertThat(request.active()).isFalse();
        assertThat(request.role()).isEqualTo(UserRole.LEADER);
        assertThat(request.teamIdPresent()).isTrue();
        assertThat(request.teamId()).isNull();
        assertThat(request.displayName()).isEqualTo("Имя");
        assertThat(withoutTeam.teamIdPresent()).isFalse();
        assertThat(withoutTeam.active()).isNull();
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"version\":0,\"accessRevision\":5}",
                AdminCrmProfileUpdateRequest.class
        )).isInstanceOf(Exception.class);
    }

    @Test
    void deactivationUnassignsEveryOwnedOrganizationAndWritesImmutableAuditEvents() {
        AdminCrmProfile deactivated = adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(0, false),
                "deactivate-user-a",
                REQUEST_ID
        );

        assertThat(deactivated.active()).isFalse();
        assertThat(deactivated.version()).isEqualTo(1);
        assertThat(deactivated.accessRevision()).isEqualTo(1);
        assertThat(ownerOf(ORGANIZATION_A)).isNull();
        assertThat(ownerOf(ORGANIZATION_A_SECOND)).isNull();
        assertThat(ownerOf(ORGANIZATION_B)).isEqualTo(USER_B);
        assertThat(versionOf(ORGANIZATION_A)).isEqualTo(1);
        assertThat(versionOf(ORGANIZATION_A_SECOND)).isEqualTo(1);
        assertThat(versionOf(ORGANIZATION_B)).isZero();
        assertThat(accessRevision(USER_A)).isEqualTo(1);
        assertThat(accessRevision(USER_B)).isZero();
        assertThat(userProfileRepository.findActiveByIdentity(ISSUER, USER_A.toString())).isEmpty();

        List<OrganizationAssignmentEvent> firstEvents = organizationAssignmentRepository
                .findEventsByOrganizationId(ORGANIZATION_A);
        List<OrganizationAssignmentEvent> secondEvents = organizationAssignmentRepository
                .findEventsByOrganizationId(ORGANIZATION_A_SECOND);
        assertThat(firstEvents).singleElement().satisfies(event -> assertAutomaticDeassignment(event, ORGANIZATION_A));
        assertThat(secondEvents).singleElement().satisfies(event -> assertAutomaticDeassignment(event, ORGANIZATION_A_SECOND));
        assertThat(firstEvents.getFirst().commandId()).isEqualTo(secondEvents.getFirst().commandId());
        assertThat(count("command_idempotency_records")).isEqualTo(1);
        assertThat(adminCrmProfileService.events(administrator, USER_A)).singleElement().satisfies(event -> {
            assertThat(event.commandId()).isEqualTo(firstEvents.getFirst().commandId());
            assertThat(event.actorProfileId()).isEqualTo(ADMIN);
            assertThat(event.actorDisplayName()).isEqualTo("Администратор");
            assertThat(event.previousActive()).isTrue();
            assertThat(event.active()).isFalse();
            assertThat(event.previousRole()).isEqualTo(UserRole.USER);
            assertThat(event.role()).isEqualTo(UserRole.USER);
            assertThat(event.teamName()).isEqualTo("Команда А");
            assertThat(event.requestId()).isEqualTo(REQUEST_ID);
            assertThat(event.version()).isEqualTo(1);
            assertThat(event.occurredAt()).isNotNull();
        });
    }

    @Test
    void repeatsTheSameCommandWithoutDuplicatingEventsAndRejectsChangedPayload() {
        AdminCrmProfile first = adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(0, false),
                "deactivate-replay",
                REQUEST_ID
        );
        AdminCrmProfile replayed = adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(0, false),
                "deactivate-replay",
                REQUEST_ID
        );

        assertThat(replayed).isEqualTo(first);
        assertThat(count("organization_assignment_events")).isEqualTo(2);
        assertThat(count("crm_profile_events")).isEqualTo(1);
        assertThat(count("command_idempotency_records")).isEqualTo(1);
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(0, true),
                "deactivate-replay",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(exception.currentVersion()).isNull();
        });
    }

    @Test
    void reportsStaleVersionAndReactivationDoesNotRestoreOrganizationAssignments() {
        adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(0, false),
                "deactivate-for-reactivation",
                REQUEST_ID
        );

        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(0, true),
                "reactivate-stale",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            assertThat(exception.currentVersion()).isEqualTo(1);
        });

        AdminCrmProfile reactivated = adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(1, true),
                "reactivate-user-a",
                REQUEST_ID
        );

        assertThat(reactivated.active()).isTrue();
        assertThat(reactivated.version()).isEqualTo(2);
        assertThat(reactivated.accessRevision()).isEqualTo(2);
        assertThat(ownerOf(ORGANIZATION_A)).isNull();
        assertThat(ownerOf(ORGANIZATION_A_SECOND)).isNull();
        assertThat(count("organization_assignment_events")).isEqualTo(2);
        assertThat(adminCrmProfileRepository.findEventsByProfileId(USER_A)).hasSize(2);
    }

    @Test
    void teamChangeRaisesAccessRevisionUnassignsOwnedOrganizationsAndNextRequestUsesTheNewScope() {
        CrmProfile before = activeProfile(USER_A);
        assertThat(organizationRepository.findVisible(before, organizationQuery()).total()).isEqualTo(2);

        AdminCrmProfile moved = adminCrmProfileService.update(
                administrator,
                USER_A,
                new AdminCrmProfileUpdateRequest(0).setTeamId(TEAM_B),
                "move-user-a",
                REQUEST_ID
        );

        CrmProfile after = activeProfile(USER_A);
        assertThat(moved.teamId()).isEqualTo(TEAM_B);
        assertThat(moved.teamName()).isEqualTo("Команда Б");
        assertThat(after.teamId()).isEqualTo(TEAM_B);
        assertThat(after.accessRevision()).isEqualTo(before.accessRevision() + 1);
        assertThat(organizationRepository.findVisible(after, organizationQuery()).total()).isZero();
        assertThat(organizationRepository.findVisibleById(after, ORGANIZATION_A)).isEmpty();
        assertThat(ownerOf(ORGANIZATION_A)).isNull();
        assertThat(ownerOf(ORGANIZATION_A_SECOND)).isNull();
        assertThat(organizationAssignmentRepository.findEventsByOrganizationId(ORGANIZATION_A)).singleElement()
                .satisfies(event -> assertThat(event.reason()).isEqualTo(OrganizationAssignmentReason.PROFILE_TEAM_CHANGED));
        assertThat(adminCrmProfileService.events(administrator, USER_A)).singleElement().satisfies(event -> {
            assertThat(event.previousTeamName()).isEqualTo("Команда А");
            assertThat(event.teamName()).isEqualTo("Команда Б");
            assertThat(event.previousActive()).isTrue();
            assertThat(event.active()).isTrue();
        });
    }

    @Test
    void roleChangeRaisesAccessRevisionAndNextRequestUsesTheNewScope() {
        CrmProfile leaderBefore = activeProfile(LEADER_A);
        assertThat(organizationRepository.findVisible(leaderBefore, organizationQuery()).total()).isEqualTo(3);

        AdminCrmProfile demoted = adminCrmProfileService.update(
                administrator,
                LEADER_A,
                new AdminCrmProfileUpdateRequest(0).setRole(UserRole.USER),
                "demote-leader",
                REQUEST_ID
        );

        CrmProfile leaderAfter = activeProfile(LEADER_A);
        assertThat(demoted.role()).isEqualTo(UserRole.USER);
        assertThat(leaderAfter.role()).isEqualTo(UserRole.USER);
        assertThat(leaderAfter.accessRevision()).isEqualTo(leaderBefore.accessRevision() + 1);
        assertThat(organizationRepository.findVisible(leaderAfter, organizationQuery()).total()).isZero();

        AdminCrmProfile renamed = adminCrmProfileService.update(
                administrator,
                LEADER_A,
                new AdminCrmProfileUpdateRequest(1).setDisplayName("Елена Менеджер"),
                "rename-former-leader",
                REQUEST_ID
        );
        assertThat(renamed.accessRevision()).isEqualTo(demoted.accessRevision());
        assertThat(adminCrmProfileService.events(administrator, LEADER_A)).hasSize(2).first().satisfies(event -> {
            assertThat(event.previousDisplayName()).isEqualTo("Елена Руководитель");
            assertThat(event.displayName()).isEqualTo("Елена Менеджер");
        });
    }

    @Test
    void enrolmentOperatorFlagIsGrantedOnlyToKamOrLeaderAndDropsWhenRoleNoLongerAllowsIt() throws Exception {
        AdminCrmProfileUpdateRequest grant = objectMapper.readValue(
                "{\"version\":0,\"enrolmentOperator\":true}", AdminCrmProfileUpdateRequest.class
        );

        AdminCrmProfile granted = adminCrmProfileService.update(administrator, USER_A, grant, "grant-operator", REQUEST_ID);

        assertThat(granted.enrolmentOperator()).isTrue();
        assertThat(granted.accessRevision()).isEqualTo(1);
        assertThat(userProfileRepository.isEnrolmentOperator(USER_A)).isTrue();
        assertThatThrownBy(() -> adminCrmProfileService.update(administrator, ADMIN,
                new AdminCrmProfileUpdateRequest(0).setEnrolmentOperator(true), "grant-self", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("enrolmentOperator"));
        assertThat(userProfileRepository.isEnrolmentOperator(ADMIN)).isFalse();

        AdminCrmProfile promoted = adminCrmProfileService.update(administrator, USER_A,
                new AdminCrmProfileUpdateRequest(1).setRole(UserRole.ADMIN).setTeamId(null), "promote-operator", REQUEST_ID);

        assertThat(promoted.enrolmentOperator()).isFalse();
        assertThat(userProfileRepository.isEnrolmentOperator(USER_A)).isFalse();
        assertThat(adminCrmProfileService.events(administrator, USER_A))
                .extracting(CrmProfileEvent::previousEnrolmentOperator, CrmProfileEvent::enrolmentOperator)
                .containsExactly(tuple(true, false), tuple(false, true));
    }

    @Test
    void activeUserOrLeaderRequiresAnExistingTeam() {
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                USER_B,
                new AdminCrmProfileUpdateRequest(0).setTeamId(null),
                "user-without-team",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("teamId"));
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                USER_B,
                new AdminCrmProfileUpdateRequest(0).setTeamId(MISSING_TEAM),
                "user-missing-team",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("teamId"));
        assertThat(profile(USER_B).teamId()).isEqualTo(TEAM_B);
        assertThat(profile(USER_B).version()).isZero();
    }

    @Test
    void lastActiveAdministratorCannotLoseAdministrationAccess() {
        insertProfile(SECOND_ADMIN, "Второй администратор", "ADMIN", null, true);
        AdminCrmProfile demoted = adminCrmProfileService.update(
                administrator,
                SECOND_ADMIN,
                new AdminCrmProfileUpdateRequest(0).setRole(UserRole.LEADER).setTeamId(TEAM_A),
                "demote-second-admin",
                REQUEST_ID
        );
        assertThat(demoted.role()).isEqualTo(UserRole.LEADER);

        jdbcTemplate.update("UPDATE crm_user_profiles SET role = 'ADMIN', team_id = NULL WHERE id = ?", SECOND_ADMIN);
        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", ADMIN);
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                SECOND_ADMIN,
                AdminCrmProfileUpdateRequest.active(1, false),
                "deactivate-last-admin",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                assertThat(exception.code()).isEqualTo("LAST_ACTIVE_ADMIN"));
        assertThat(profile(SECOND_ADMIN).active()).isTrue();
    }

    @Test
    void firstLoginCreatesPendingProfileWithoutAccessUntilAdministratorActivatesIt() throws Exception {
        OidcUser newcomer = oidcUser("newcomer-subject", "Новый Сотрудник", "newcomer");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();

        new CrmProfileRegistrationSuccessHandler(currentProfileService).onAuthenticationSuccess(
                new MockHttpServletRequest(),
                loginResponse,
                new OAuth2AuthenticationToken(newcomer, List.of(), "keycloak")
        );

        assertThat(loginResponse.getRedirectedUrl()).isEqualTo("/");
        assertThat(currentProfileService.registerPendingProfile(newcomer)).isFalse();
        assertThat(currentProfileService.registerPendingProfile(oidcUser(USER_A.toString(), "Чужое имя", "other"))).isFalse();
        assertThat(profile(USER_A).displayName()).isEqualTo("Анна Менеджер");
        assertThat(profile(USER_A).active()).isTrue();

        assertThatThrownBy(() -> currentProfileService.requireActiveProfile(newcomer))
                .isInstanceOf(CrmProfilePendingException.class);
        assertThatThrownBy(() -> currentProfileService.requireActiveProfile(oidcUser("unknown", null, null)))
                .isInstanceOf(CrmProfileNotFoundException.class);
        AdminCrmProfilePage pendingPage = adminCrmProfileService.list(administrator, pendingQuery());
        AdminCrmProfile pending = pendingPage.items().getFirst();
        assertThat(pendingPage.pendingTotal()).isEqualTo(1);
        assertThat(pending.displayName()).isEqualTo("Новый Сотрудник");
        assertThat(pending.login()).isEqualTo("newcomer");
        assertThat(pending.accountSyncRequired()).isFalse();
        assertThat(profile(USER_A).login()).isEqualTo("other");
        assertThat(adminCrmProfileService.list(administrator, AdminCrmProfileQuery.from(0, 25, "displayName,asc", false, "NEWCOMER"))
                .items()).extracting(AdminCrmProfile::id).containsExactly(pending.id());
        assertThat(adminCrmProfileService.list(administrator, AdminCrmProfileQuery.from(0, 25, "displayName,asc", false, "сотрудник"))
                .items()).extracting(AdminCrmProfile::id).containsExactly(pending.id());
        assertThat(adminCrmProfileService.list(administrator, AdminCrmProfileQuery.from(0, 25, "displayName,asc", false, "100%"))
                .total()).isZero();
        assertThat(pending.role()).isEqualTo(UserRole.USER);
        assertThat(pending.teamId()).isNull();
        assertThat(pending.active()).isFalse();
        assertThat(pending.pendingActivation()).isTrue();

        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                pending.id(),
                AdminCrmProfileUpdateRequest.active(0, true),
                "activate-without-team",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("teamId"));

        AdminCrmProfile activated = adminCrmProfileService.update(
                administrator,
                pending.id(),
                new AdminCrmProfileUpdateRequest(0).setTeamId(TEAM_A).setActive(true),
                "activate-newcomer",
                REQUEST_ID
        );
        assertThat(activated.active()).isTrue();
        assertThat(activated.pendingActivation()).isFalse();
        assertThat(currentProfileService.requireActiveProfile(newcomer).teamId()).isEqualTo(TEAM_A);
        assertThat(adminCrmProfileService.list(administrator, pendingQuery()).total()).isZero();

        adminCrmProfileService.update(
                administrator,
                pending.id(),
                AdminCrmProfileUpdateRequest.active(1, false),
                "block-newcomer",
                REQUEST_ID
        );
        assertThatThrownBy(() -> currentProfileService.requireActiveProfile(newcomer))
                .isInstanceOf(CrmProfileNotFoundException.class);
    }

    @Test
    void trustedBootstrapActivatesOnlyAProfileStillPendingActivation() {
        OidcUser earlyAdministrator = oidcUser("early-admin", "Ранний вход", "admin");
        assertThat(currentProfileService.registerPendingProfile(earlyAdministrator)).isTrue();

        assertThat(userProfileRepository.activatePending(ISSUER, "early-admin", "Администратор", UserRole.ADMIN, null)).isTrue();
        assertThat(userProfileRepository.activatePending(ISSUER, "early-admin", "Другое имя", UserRole.USER, TEAM_A)).isFalse();
        CrmProfile activated = currentProfileService.requireActiveProfile(earlyAdministrator);
        assertThat(activated.role()).isEqualTo(UserRole.ADMIN);
        assertThat(activated.teamId()).isNull();
        assertThat(profile(activated.id()).displayName()).isEqualTo("Администратор");
        assertThat(profile(activated.id()).pendingActivation()).isFalse();
        assertThat(adminCrmProfileService.list(administrator, pendingQuery()).total()).isZero();

        adminCrmProfileService.update(administrator, USER_A, AdminCrmProfileUpdateRequest.active(0, false), "block-user-a", REQUEST_ID);
        assertThat(userProfileRepository.activatePending(ISSUER, USER_A.toString(), "Анна Менеджер", UserRole.USER, TEAM_A)).isFalse();
        assertThat(profile(USER_A).active()).isFalse();
    }

    @Test
    void administratorCreatesAndRenamesTeamsWithUniqueNamesAndVersions() {
        Team created = adminTeamService.create(administrator, new TeamRequest(" Команда В ", null), "create-team-c", REQUEST_ID);
        Team replayed = adminTeamService.create(administrator, new TeamRequest(" Команда В ", null), "create-team-c", REQUEST_ID);

        assertThat(created.name()).isEqualTo("Команда В");
        assertThat(replayed).isEqualTo(created);
        assertThat(adminTeamService.list(administrator)).extracting(AdminTeam::name)
                .containsExactly("Команда А", "Команда Б", "Команда В");
        assertThatThrownBy(() -> adminTeamService.create(administrator, new TeamRequest("команда а", null), "create-duplicate", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("name"));

        Team renamed = adminTeamService.rename(
                administrator, created.id(), new TeamRequest("Команда Восток", 0), "rename-team-c", REQUEST_ID
        );
        assertThat(renamed.version()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events ORDER BY action", String.class))
                .containsExactly("TEAM_CREATED", "TEAM_RENAMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT details FROM audit_events WHERE action = 'TEAM_RENAMED'", String.class
        )).isEqualTo("название: Команда В → Команда Восток");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor_display_name FROM audit_events WHERE action = 'TEAM_CREATED'", String.class
        )).isEqualTo("Администратор");
        assertThatThrownBy(() -> adminTeamService.rename(
                administrator,
                created.id(),
                new TeamRequest("Команда Запад", 0),
                "rename-stale",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
            assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
            assertThat(exception.currentVersion()).isEqualTo(1);
        });
        assertThatThrownBy(() -> adminTeamService.rename(
                administrator,
                created.id(),
                new TeamRequest("Команда Б", 1),
                "rename-duplicate",
                REQUEST_ID
        )).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> adminTeamService.rename(
                administrator,
                MISSING_TEAM,
                new TeamRequest("Нет такой", 0),
                "rename-missing",
                REQUEST_ID
        )).isInstanceOf(TeamNotFoundException.class);
    }

    @Test
    void teamListShowsCompositionAndOnlyAnEmptyTeamIsArchivedWithJournal() {
        insertProfile(LEADER_B, "Борис Руководитель", "LEADER", TEAM_B, true);
        insertProfile(UUID.fromString("20000000-0000-0000-0000-000000000007"), "Отключённый КАМ", "USER", TEAM_A, false);
        Team empty = adminTeamService.create(administrator, new TeamRequest("UAT Команда проверки", null), "create-empty", REQUEST_ID);

        assertThat(adminTeamService.list(administrator))
                .extracting(AdminTeam::name, AdminTeam::leaderNames, AdminTeam::managerNames, AdminTeam::organizationCount,
                        AdminTeam::otherProfileCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Команда А", List.of("Елена Руководитель"), List.of("Анна Менеджер"), 3L, 1L),
                        org.assertj.core.groups.Tuple.tuple("Команда Б", List.of("Борис Руководитель"), List.of("Борис Менеджер"), 1L, 0L),
                        org.assertj.core.groups.Tuple.tuple("UAT Команда проверки", List.of(), List.of(), 0L, 0L)
                );
        assertThatThrownBy(() -> adminTeamService.list(leaderA)).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> adminTeamService.changeArchived(
                administrator, TEAM_A, new TeamArchiveRequest(true, 0), "archive-busy", REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.getMessage()).contains("организации (3)"));

        Team archived = adminTeamService.changeArchived(
                administrator, empty.id(), new TeamArchiveRequest(true, 0), "archive-empty", REQUEST_ID
        );
        Team replayed = adminTeamService.changeArchived(
                administrator, empty.id(), new TeamArchiveRequest(true, 0), "archive-empty", REQUEST_ID
        );

        assertThat(archived.archived()).isTrue();
        assertThat(replayed).isEqualTo(archived);
        assertThatThrownBy(() -> adminTeamService.changeArchived(
                administrator, empty.id(), new TeamArchiveRequest(false, 0), "restore-stale", REQUEST_ID
        )).isInstanceOf(InteractionConflictException.class);
        assertThatThrownBy(() -> adminCrmProfileService.update(
                administrator,
                USER_A,
                new AdminCrmProfileUpdateRequest(0).setTeamId(empty.id()),
                "move-to-archived",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("teamId"));
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM catalog_change_events WHERE entity_id = ?", String.class, empty.id()
        )).containsExactlyInAnyOrder("CREATE", "ARCHIVE");
    }

    @Test
    void organizationTransferRemovesForeignOwnerAndChangesTeamVisibility() {
        insertProfile(LEADER_B, "Борис Руководитель", "LEADER", TEAM_B, true);
        assertThat(organizationRepository.findVisibleById(leaderA, ORGANIZATION_A)).isPresent();
        assertThat(organizationRepository.findVisibleById(activeProfile(LEADER_B), ORGANIZATION_A)).isEmpty();

        AdminOrganization transferred = adminOrganizationService.transferTeam(
                administrator,
                ORGANIZATION_A,
                new AdminOrganizationTeamRequest(TEAM_B, 0),
                "transfer-a",
                REQUEST_ID
        );
        AdminOrganization replayed = adminOrganizationService.transferTeam(
                administrator,
                ORGANIZATION_A,
                new AdminOrganizationTeamRequest(TEAM_B, 0),
                "transfer-a",
                REQUEST_ID
        );

        assertThat(replayed).isEqualTo(transferred);
        assertThat(transferred.teamId()).isEqualTo(TEAM_B);
        assertThat(transferred.teamName()).isEqualTo("Команда Б");
        assertThat(transferred.ownerManagerId()).isNull();
        assertThat(transferred.version()).isEqualTo(1);
        assertThat(organizationRepository.findVisibleById(activeProfile(LEADER_A), ORGANIZATION_A)).isEmpty();
        assertThat(organizationRepository.findVisibleById(activeProfile(USER_A), ORGANIZATION_A)).isEmpty();
        assertThat(organizationRepository.findVisibleById(activeProfile(LEADER_B), ORGANIZATION_A))
                .map(Organization::teamId)
                .contains(TEAM_B);
        assertThat(accessRevision(USER_A)).isEqualTo(1);
        assertThat(accessRevision(LEADER_A)).isEqualTo(1);
        assertThat(accessRevision(LEADER_B)).isEqualTo(1);
        assertThat(accessRevision(USER_B)).isZero();
        assertThat(organizationAssignmentRepository.findEventsByOrganizationId(ORGANIZATION_A)).singleElement()
                .satisfies(event -> {
                    assertThat(event.previousOwnerManagerId()).isEqualTo(USER_A);
                    assertThat(event.ownerManagerId()).isNull();
                    assertThat(event.actorProfileId()).isEqualTo(ADMIN);
                });
        assertThat(count("organization_team_events")).isEqualTo(1);

        assertThatThrownBy(() -> adminOrganizationService.transferTeam(
                administrator,
                ORGANIZATION_A,
                new AdminOrganizationTeamRequest(TEAM_A, 0),
                "transfer-stale",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                assertThat(exception.currentVersion()).isEqualTo(1));
        assertThatThrownBy(() -> adminOrganizationService.transferTeam(
                administrator,
                ORGANIZATION_A,
                new AdminOrganizationTeamRequest(TEAM_B, 1),
                "transfer-same",
                REQUEST_ID
        )).isInstanceOf(InteractionValidationException.class);
        assertThat(adminOrganizationService.list(administrator, organizationQuery()).items())
                .filteredOn(organization -> organization.id().equals(ORGANIZATION_B))
                .singleElement()
                .satisfies(organization -> assertThat(organization.ownerManagerName()).isEqualTo("Борис Менеджер"));
    }

    @Test
    void assignmentCannotMakeADeactivatedUserTheOrganizationOwner() {
        adminCrmProfileService.update(
                administrator,
                USER_A,
                AdminCrmProfileUpdateRequest.active(0, false),
                "deactivate-before-assignment",
                REQUEST_ID
        );

        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_UNASSIGNED,
                new OrganizationAssignmentRequest(0, USER_A),
                "assign-inactive-user",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("ownerManagerId");
        });
        assertThat(ownerOf(ORGANIZATION_UNASSIGNED)).isNull();
        assertThat(versionOf(ORGANIZATION_UNASSIGNED)).isZero();
    }

    @Test
    void nonexistentAssignmentCandidateReturnsAValidationError() {
        assertThatThrownBy(() -> organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_UNASSIGNED,
                new OrganizationAssignmentRequest(0, UUID.fromString("20000000-0000-0000-0000-000000000099")),
                "assign-missing-user",
                REQUEST_ID
        )).isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
            assertThat(exception.field()).isEqualTo("ownerManagerId");
        });
        assertThat(ownerOf(ORGANIZATION_UNASSIGNED)).isNull();
        assertThat(versionOf(ORGANIZATION_UNASSIGNED)).isZero();
    }

    @Test
    void leaderOwnershipSurvivesRenameAndIsRemovedOnDeactivationLikeManagerOwnership() {
        organizationAssignmentService.assign(
                leaderA,
                ORGANIZATION_UNASSIGNED,
                new OrganizationAssignmentRequest(0, LEADER_A),
                "assign-leader-self",
                REQUEST_ID
        );
        AdminCrmProfile renamed = adminCrmProfileService.update(
                administrator,
                LEADER_A,
                new AdminCrmProfileUpdateRequest(0).setDisplayName("Елена Руководитель команды"),
                "rename-owning-leader",
                REQUEST_ID
        );

        assertThat(renamed.version()).isEqualTo(1);
        assertThat(ownerOf(ORGANIZATION_UNASSIGNED)).isEqualTo(LEADER_A);

        adminCrmProfileService.update(
                administrator,
                LEADER_A,
                AdminCrmProfileUpdateRequest.active(1, false),
                "deactivate-owning-leader",
                REQUEST_ID
        );

        assertThat(ownerOf(ORGANIZATION_UNASSIGNED)).isNull();
        assertThat(versionOf(ORGANIZATION_UNASSIGNED)).isEqualTo(2);
        assertThat(organizationAssignmentRepository.findEventsByOrganizationId(ORGANIZATION_UNASSIGNED))
                .hasSize(2)
                .last()
                .satisfies(event -> {
                    assertThat(event.previousOwnerManagerId()).isEqualTo(LEADER_A);
                    assertThat(event.ownerManagerId()).isNull();
                    assertThat(event.actorProfileId()).isEqualTo(ADMIN);
                });
    }

    private void assertAutomaticDeassignment(OrganizationAssignmentEvent event, UUID organizationId) {
        assertThat(event.organizationId()).isEqualTo(organizationId);
        assertThat(event.previousOwnerManagerId()).isEqualTo(USER_A);
        assertThat(event.previousOwnerManagerDisplayName()).isEqualTo("Анна Менеджер");
        assertThat(event.ownerManagerId()).isNull();
        assertThat(event.newOwnerManagerDisplayName()).isNull();
        assertThat(event.actorProfileId()).isEqualTo(ADMIN);
        assertThat(event.actorDisplayName()).isEqualTo("Администратор");
        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
        assertThat(event.reason()).isEqualTo(OrganizationAssignmentReason.PROFILE_BLOCKED);
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isNotNull();
    }

    private OidcUser oidcUser(String subject, String name, String preferredUsername) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", subject);
        if (name != null) {
            claims.put("name", name);
        }
        if (preferredUsername != null) {
            claims.put("preferred_username", preferredUsername);
        }
        return new DefaultOidcUser(
                List.of(),
                new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60), claims)
        );
    }

    private CrmProfile activeProfile(UUID profileId) {
        return userProfileRepository.findActiveByIdentity(ISSUER, profileId.toString()).orElseThrow();
    }

    private OrganizationQuery organizationQuery() {
        return OrganizationQuery.from(0, 25, "name,asc", null, false);
    }

    private AdminCrmProfileQuery pendingQuery() {
        return AdminCrmProfileQuery.from(0, 25, "displayName,asc", true, null);
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS catalog_change_events (
                    id UUID PRIMARY KEY, entity_type VARCHAR(16) NOT NULL, entity_id UUID NOT NULL,
                    action VARCHAR(16) NOT NULL, entity_name VARCHAR(300) NOT NULL, changes VARCHAR(2000),
                    actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY,
                    name VARCHAR(160) NOT NULL UNIQUE,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, archived BOOLEAN DEFAULT FALSE NOT NULL, default_workflow_template_id UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY,
                    login VARCHAR(200),
                    idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE,
                    issuer VARCHAR(512) NOT NULL,
                    subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    access_revision INTEGER NOT NULL DEFAULT 0,
                    version INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (issuer, subject),
                    CHECK (NOT (pending_activation AND active))
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
                    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
                    previous_owner_manager_id UUID,
                    previous_owner_manager_display_name VARCHAR(200),
                    owner_manager_id UUID,
                    new_owner_manager_display_name VARCHAR(200),
                    actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (command_id, organization_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_profile_events (previous_enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY,
                    profile_id UUID NOT NULL,
                    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
                    actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL,
                    previous_display_name VARCHAR(200) NOT NULL,
                    display_name VARCHAR(200) NOT NULL,
                    previous_role VARCHAR(16) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    previous_team_id UUID,
                    team_id UUID,
                    previous_active BOOLEAN NOT NULL,
                    active BOOLEAN NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (command_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY,
                    category VARCHAR(32) NOT NULL,
                    action VARCHAR(64) NOT NULL,
                    actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL,
                    object_type VARCHAR(32),
                    object_id UUID,
                    object_name VARCHAR(500),
                    details VARCHAR(2000),
                    request_id VARCHAR(64),
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_team_events (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
                    previous_team_id UUID NOT NULL,
                    team_id UUID NOT NULL,
                    previous_owner_manager_id UUID,
                    actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (command_id)
                )
                """);
    }

    private void insertTeam(UUID id, String name) {
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?)", id, name);
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId, boolean active) {
        jdbcTemplate.update(
                """
                        INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                ISSUER,
                id.toString(),
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

    private AdminCrmProfile profile(UUID profileId) {
        return adminCrmProfileRepository.findById(profileId).orElseThrow();
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
