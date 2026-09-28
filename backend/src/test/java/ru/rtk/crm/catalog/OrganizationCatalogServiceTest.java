package ru.rtk.crm.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:organization_catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OrganizationRepository.class,
        AdminOrganizationRepository.class,
        OrganizationCatalogRepository.class,
        OrganizationAssignmentRepository.class,
        CatalogChangeEventRepository.class,
        CommandIdempotencyRepository.class,
        IdempotentCommandRunner.class,
        OrganizationCatalogService.class,
        OrganizationCatalogServiceTest.JsonConfiguration.class
})
class OrganizationCatalogServiceTest {
    private static final UUID TEAM_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ARCHIVED_TEAM = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID LEADER_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID LEADER_B = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID UNIVERSITY_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID UNIVERSITY_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000999";

    private final CrmProfile leaderA = new CrmProfile(LEADER_A, UserRole.LEADER, TEAM_A, 0);
    private final CrmProfile leaderB = new CrmProfile(LEADER_B, UserRole.LEADER, TEAM_B, 0);
    private final CrmProfile userA = new CrmProfile(USER_A, UserRole.USER, TEAM_A, 0);
    private final CrmProfile userB = new CrmProfile(USER_B, UserRole.USER, TEAM_B, 0);
    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);

    @Autowired
    private OrganizationCatalogService service;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private AdminOrganizationRepository adminOrganizationRepository;

    @Autowired
    private OrganizationCatalogRepository organizationCatalogRepository;

    @Autowired
    private OrganizationAssignmentRepository organizationAssignmentRepository;

    @Autowired
    private CatalogChangeEventRepository catalogChangeEventRepository;

    @Autowired
    private IdempotentCommandRunner commandRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of(
                "catalog_change_events", "command_idempotency_records", "organizations", "crm_user_profiles", "teams"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO teams (id, name, archived) VALUES (?, 'team-a', FALSE), (?, 'team-b', FALSE), (?, 'old', TRUE)",
                TEAM_A, TEAM_B, ARCHIVED_TEAM);
        insertProfile(LEADER_A, "Руководитель А", "LEADER", TEAM_A);
        insertProfile(USER_A, "КАМ А", "USER", TEAM_A);
        insertProfile(USER_B, "КАМ Б", "USER", TEAM_B);
        insertProfile(LEADER_B, "Руководитель Б", "LEADER", TEAM_B);
        insertProfile(ADMIN, "Администратор", "ADMIN", null);
        insertOrganization(UNIVERSITY_A, "Университет «А»", TEAM_A, USER_A);
        insertOrganization(UNIVERSITY_B, "Университет Б", TEAM_B, USER_B);
    }

    @Test
    void managerRequestIsVisibleToTeamAndBecomesActiveAfterLeaderApproval() {
        OrganizationCommandResult created = service.create(userA, request("  UAT Школа  № 1 ", OrganizationType.SCHOOL,
                "Москва", "school1.example.test", null), "school-request", REQUEST_ID);
        OrganizationCommandResult replayed = service.create(userA, request("  UAT Школа  № 1 ", OrganizationType.SCHOOL,
                "Москва", "school1.example.test", null), "school-request", REQUEST_ID);

        assertThat(replayed).isEqualTo(created);
        Organization pending = organizationRepository.findVisibleById(userA, created.id()).orElseThrow();
        assertThat(pending.name()).isEqualTo("UAT Школа № 1");
        assertThat(pending.status()).isEqualTo(OrganizationStatus.PENDING);
        assertThat(pending.ownerManagerId()).isEqualTo(USER_A);
        assertThat(pending.website()).isEqualTo("https://school1.example.test");
        assertThat(organizationRepository.findVisible(leaderA, OrganizationQuery.from(0, 25, "name,asc", null, false, "PENDING"))
                .items()).extracting(Organization::id).containsExactly(created.id());
        assertThat(organizationRepository.findVisibleById(leaderB, created.id())).isEmpty();
        assertThat(accessRevision(USER_A)).isEqualTo(1);

        assertThatThrownBy(() -> service.changeStatus(userA, created.id(),
                new OrganizationStatusRequest(OrganizationStatusAction.APPROVE, 0, null), "self-approve", REQUEST_ID))
                .isInstanceOf(CatalogChangeAccessDeniedException.class);
        assertThatThrownBy(() -> service.changeStatus(leaderB, created.id(),
                new OrganizationStatusRequest(OrganizationStatusAction.APPROVE, 0, null), "foreign-approve", REQUEST_ID))
                .isInstanceOf(OrganizationNotFoundException.class);

        service.changeStatus(leaderA, created.id(),
                new OrganizationStatusRequest(OrganizationStatusAction.APPROVE, 0, null), "approve", REQUEST_ID);

        assertThat(organizationRepository.findVisibleById(userA, created.id()).orElseThrow().status())
                .isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(catalogChangeEventRepository.findPage(CatalogEntityType.ORGANIZATION, 0, 10).items())
                .extracting(CatalogChangeEvent::action, CatalogChangeEvent::actorDisplayName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CatalogChangeAction.APPROVE, "Руководитель А"),
                        org.assertj.core.groups.Tuple.tuple(CatalogChangeAction.REQUEST, "КАМ А")
                );
    }

    @Test
    void duplicatesAreComparedByNormalizedNameAndForeignOnesDoNotLeakLinks() {
        assertThatThrownBy(() -> service.create(leaderA, request("университет  \"а\"", OrganizationType.UNIVERSITY,
                null, null, null), "duplicate", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("name");
                    assertThat(exception).hasMessageContaining("Университет «А»");
                });
        assertThat(service.findDuplicates(userB, "Университет А", null))
                .singleElement()
                .satisfies(duplicate -> {
                    assertThat(duplicate.exact()).isTrue();
                    assertThat(duplicate.id()).isNull();
                    assertThat(duplicate.teamName()).isEqualTo("team-a");
                });
        assertThat(service.findDuplicates(leaderA, "Университет", null))
                .extracting(OrganizationDuplicate::id)
                .containsExactly(UNIVERSITY_A);
        assertThat(count("organizations")).isEqualTo(2);
    }

    @Test
    void leaderEditsDetailsWithVersionCheckAndManagerCannot() {
        assertThatThrownBy(() -> service.update(userA, UNIVERSITY_A, request("Университет А (испр.)",
                OrganizationType.UNIVERSITY, null, null, null, 0), "manager-edit", REQUEST_ID))
                .isInstanceOf(CatalogChangeAccessDeniedException.class);
        assertThatThrownBy(() -> service.update(leaderB, UNIVERSITY_A, request("Чужой",
                OrganizationType.UNIVERSITY, null, null, null, 0), "foreign-edit", REQUEST_ID))
                .isInstanceOf(OrganizationNotFoundException.class);
        assertThatThrownBy(() -> service.update(leaderA, UNIVERSITY_A, request("Университет А",
                OrganizationType.UNIVERSITY, null, null, "123", 0), "bad-inn", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                        assertThat(exception.field()).isEqualTo("inn"));

        service.update(leaderA, UNIVERSITY_A, request("Университет А (испр.)", OrganizationType.UNIVERSITY,
                "Казань", null, "1655000000", 0), "leader-edit", REQUEST_ID);

        assertThatThrownBy(() -> service.update(leaderA, UNIVERSITY_A, request("Университет А2",
                OrganizationType.UNIVERSITY, null, null, null, 0), "stale-edit", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(exception.currentVersion()).isEqualTo(1);
                });
        assertThatThrownBy(() -> service.update(leaderA, UNIVERSITY_A, request("Другое имя",
                OrganizationType.UNIVERSITY, null, null, null, 1), "leader-edit", REQUEST_ID))
                .isInstanceOfSatisfying(InteractionConflictException.class, exception ->
                        assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        Organization updated = organizationRepository.findVisibleById(leaderA, UNIVERSITY_A).orElseThrow();
        assertThat(updated.name()).isEqualTo("Университет А (испр.)");
        assertThat(updated.inn()).isEqualTo("1655000000");
        assertThat(catalogChangeEventRepository.findPage(null, 0, 10).items()).singleElement()
                .satisfies(event -> assertThat(event.changes())
                        .contains("Название: «Университет «А»» → «Университет А (испр.)»")
                        .contains("ИНН: — → «1655000000»"));
    }

    @Test
    void administratorCreatesCollegeInActiveTeamAndArchivesAndRestoresIt() {
        assertThatThrownBy(() -> service.createAsAdmin(userA, request("Колледж", OrganizationType.COLLEGE, null, null, null),
                "not-admin", REQUEST_ID)).isInstanceOf(AdminCrmProfileAccessDeniedException.class);
        assertThatThrownBy(() -> service.create(admin, request("Колледж", OrganizationType.COLLEGE, null, null, null),
                "admin-business", REQUEST_ID)).isInstanceOf(CatalogChangeAccessDeniedException.class);
        assertThatThrownBy(() -> service.createAsAdmin(admin, requestForTeam("Колледж связи", ARCHIVED_TEAM),
                "archived-team", REQUEST_ID)).isInstanceOfSatisfying(InteractionValidationException.class, exception ->
                assertThat(exception.field()).isEqualTo("teamId"));

        OrganizationCommandResult created = service.createAsAdmin(admin, requestForTeam("UAT Колледж связи", TEAM_A),
                "college", REQUEST_ID);
        AdminOrganization college = adminOrganizationRepository.findById(created.id()).orElseThrow();
        assertThat(college.type()).isEqualTo(OrganizationType.COLLEGE);
        assertThat(college.status()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(college.ownerManagerId()).isNull();
        assertThat(organizationRepository.findVisible(leaderA,
                OrganizationQuery.from(0, 25, "name,asc", null, true)).items())
                .extracting(Organization::id).contains(created.id());

        service.changeStatus(leaderA, created.id(), new OrganizationStatusRequest(OrganizationStatusAction.ARCHIVE, 0, "тест"),
                "archive", REQUEST_ID);
        assertThat(organizationRepository.findVisible(leaderA, OrganizationQuery.from(0, 25, "name,asc", null, false)).items())
                .extracting(Organization::id).doesNotContain(created.id());
        assertThat(organizationRepository.findVisible(leaderA, OrganizationQuery.from(0, 25, "name,asc", null, false, "ARCHIVED"))
                .items()).extracting(Organization::id).containsExactly(created.id());
        assertThatThrownBy(() -> service.changeStatus(userA, UNIVERSITY_A,
                new OrganizationStatusRequest(OrganizationStatusAction.RESTORE, 0, null), "manager-restore", REQUEST_ID))
                .isInstanceOf(CatalogChangeAccessDeniedException.class);

        service.changeStatusAsAdmin(admin, created.id(), new OrganizationStatusRequest(OrganizationStatusAction.RESTORE, 1, null),
                "restore", REQUEST_ID);
        assertThat(adminOrganizationRepository.findById(created.id()).orElseThrow().status()).isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void managerCreatesActiveOrganizationWhenApprovalIsSwitchedOff() {
        OrganizationCatalogService withoutApproval = new OrganizationCatalogService(
                organizationRepository,
                adminOrganizationRepository,
                organizationCatalogRepository,
                organizationAssignmentRepository,
                catalogChangeEventRepository,
                commandRunner,
                event -> {
                },
                false
        );

        OrganizationCommandResult created = withoutApproval.create(userA, request("Колледж без подтверждения",
                OrganizationType.COLLEGE, null, null, null), "no-approval", REQUEST_ID);

        assertThat(organizationRepository.findVisibleById(userA, created.id()).orElseThrow().status())
                .isEqualTo(OrganizationStatus.ACTIVE);
        service.changeStatus(userA, created.id(), new OrganizationStatusRequest(OrganizationStatusAction.ARCHIVE, 0, null),
                "manager-archive", REQUEST_ID);
        assertThat(organizationRepository.findVisibleById(userA, created.id()).orElseThrow().status())
                .isEqualTo(OrganizationStatus.ARCHIVED);
    }

    private static OrganizationDetailsRequest request(String name, OrganizationType type, String city, String website, String inn) {
        return new OrganizationDetailsRequest(name, type, city, website, inn, null, null);
    }

    private static OrganizationDetailsRequest request(
            String name,
            OrganizationType type,
            String city,
            String website,
            String inn,
            int version
    ) {
        return new OrganizationDetailsRequest(name, type, city, website, inn, null, version);
    }

    private static OrganizationDetailsRequest requestForTeam(String name, UUID teamId) {
        return new OrganizationDetailsRequest(name, OrganizationType.COLLEGE, null, null, null, teamId, null);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private int accessRevision(UUID profileId) {
        return jdbcTemplate.queryForObject("SELECT access_revision FROM crm_user_profiles WHERE id = ?", Integer.class, profileId);
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, display_name, role, team_id, active) VALUES (?, ?, ?, ?, TRUE)
                """, id, displayName, role, teamId);
    }

    private void insertOrganization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, 'UNIVERSITY', ?, ?, 0, CURRENT_TIMESTAMP)
                """, id, name, teamId, ownerManagerId);
    }

    private void createSchema() {
        List.of(
                """
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL, access_revision INTEGER DEFAULT 0 NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL UNIQUE, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, version INTEGER NOT NULL, status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL,
                    city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12),
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(10000), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS catalog_change_events (
                    id UUID PRIMARY KEY, entity_type VARCHAR(16) NOT NULL, entity_id UUID NOT NULL,
                    action VARCHAR(16) NOT NULL, entity_name VARCHAR(300) NOT NULL, changes VARCHAR(2000),
                    actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                "CREATE TABLE IF NOT EXISTS catalog_name_locks (entity_type VARCHAR(16) PRIMARY KEY)",
                """
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    command_id UUID NOT NULL, actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, ended_at TIMESTAMP WITH TIME ZONE,
                    ended_by_profile_id UUID, ended_by_display_name VARCHAR(200)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, previous_owner_manager_id UUID,
                    owner_manager_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                "MERGE INTO catalog_name_locks KEY (entity_type) VALUES ('ORGANIZATION'), ('DIRECTION'), ('PROGRAM'), ('VENDOR'), ('PRODUCT')"
        ).forEach(jdbcTemplate::execute);
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
