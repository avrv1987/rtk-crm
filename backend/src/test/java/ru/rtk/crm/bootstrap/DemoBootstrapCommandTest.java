package ru.rtk.crm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.attachment.AttachmentService;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactCreateRequest;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionFlagsRequest;
import ru.rtk.crm.interaction.InteractionRiskLevel;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.ProductAgreementService;
import ru.rtk.crm.interaction.ProductAgreementUpdateRequest;
import ru.rtk.crm.interaction.ProductTransferStatus;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:demo-bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DemoBootstrapCommandTest {
    private static final String ISSUER = "http://rtk.localhost:8081/idp/realms/rtk-crm";
    private static final List<DemoBootstrapProperties.Team> TEAMS = List.of(
            new DemoBootstrapProperties.Team("team-a", "Команда А"),
            new DemoBootstrapProperties.Team("team-b", "Команда Б")
    );
    private static final List<DemoBootstrapProperties.Identity> DEMO_IDENTITIES = List.of(
            identity("kam-a", "КАМ А", UserRole.USER, "team-a"),
            identity("kam-b", "КАМ Б", UserRole.USER, "team-b"),
            identity("kam-c", "КАМ В", UserRole.USER, "team-a"),
            identity("kam-d", "КАМ Г", UserRole.USER, "team-a"),
            identity("leader", "Руководитель", UserRole.LEADER, "team-a"),
            identity("leader-b", "Руководитель Б", UserRole.LEADER, "team-b"),
            identity("admin", "Администратор", UserRole.ADMIN, "team-a")
    );
    private static final List<DemoBootstrapProperties.Organization> DEMO_ORGANIZATIONS = List.of(
            new DemoBootstrapProperties.Organization("Университет А", "UNIVERSITY", "team-a", "kam-a"),
            new DemoBootstrapProperties.Organization("Университет Б", "UNIVERSITY", "team-b", "kam-b"),
            new DemoBootstrapProperties.Organization("Университет C — требует назначения", "UNIVERSITY", "team-a", null),
            new DemoBootstrapProperties.Organization("Школа № 1 (демо)", "SCHOOL", "team-a", "kam-d"),
            new DemoBootstrapProperties.Organization("Колледж связи (демо)", "COLLEGE", "team-b", "kam-b")
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    private final ContactService contactService = mock(ContactService.class);
    private final InteractionService interactionService = mock(InteractionService.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final ProductAgreementService productAgreementService = mock(ProductAgreementService.class);

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of("product_agreements", "agreements", "attachments", "contacts", "interactions", "products", "vendors", "programs", "directions",
                "organizations", "crm_user_profiles", "teams")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        when(contactService.create(any(), any(), any(ContactCreateRequest.class), anyString())).thenAnswer(invocation -> {
            ContactCreateRequest request = invocation.getArgument(2);
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO contacts (id, organization_id, name) VALUES (?, ?, ?)",
                    id, invocation.getArgument(1), request.name());
            return new Contact(id, invocation.getArgument(1), request.name(), null, null, null, 0, null,
                    OffsetDateTime.now(), OffsetDateTime.now(), null, false, false, null, null, null, PersonalDataStatus.ACTIVE);
        });
        when(interactionService.create(any(), any(InteractionCreateRequest.class), anyString())).thenAnswer(invocation -> {
            InteractionCreateRequest request = invocation.getArgument(1);
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO interactions (id, organization_id, title, next_action, current_stage_id) VALUES (?, ?, ?, ?, ?)",
                    id, request.organizationId(), request.title(), request.nextAction(), UUID.randomUUID());
            for (UUID productId : request.productIds()) {
                jdbcTemplate.update("INSERT INTO product_agreements (id, interaction_id, product_id) VALUES (?, ?, ?)",
                        UUID.randomUUID(), id, productId);
            }
            return null;
        });
        when(interactionService.updateFlags(any(), any(), any(InteractionFlagsRequest.class), anyString())).thenAnswer(invocation -> {
            InteractionFlagsRequest request = invocation.getArgument(2);
            assertThat(request.version()).isEqualTo(0);
            jdbcTemplate.update("UPDATE interactions SET problem = ?, risk_level = ?, risk_reason = ?, version = version + 1 WHERE id = ?",
                    request.problem(), request.riskLevel().name(), request.riskReason(), invocation.getArgument(1));
            return null;
        });
        when(productAgreementService.update(any(), any(), any(), any(ProductAgreementUpdateRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    ProductAgreementUpdateRequest request = invocation.getArgument(3);
                    jdbcTemplate.update("""
                            UPDATE product_agreements SET contract_number = ?, license_expiry_year = ?, transferred_kinds = ?
                            WHERE id = ?
                            """, request.contract().contractNumber(), request.contract().licenseExpiryYear(),
                            request.transfers().stream().filter(transfer -> transfer.status() == ProductTransferStatus.TRANSFERRED)
                                    .count(), invocation.getArgument(2));
                    jdbcTemplate.update("UPDATE interactions SET version = version + 1 WHERE id = ?", invocation.<UUID>getArgument(1));
                    return null;
                });
        when(attachmentService.upload(any(), any(), any(), any(), anyString())).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(3);
            assertThat(new String(file.getBytes(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            jdbcTemplate.update("INSERT INTO attachments (id, interaction_id, original_name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), invocation.getArgument(1), file.getOriginalFilename());
            return null;
        });
    }

    @Test
    void demoDataHasNamedTeamsThreeManagersInTeamASecondLeaderSchoolAndCollegeAndRepeatsWithoutDuplicates() {
        DemoBootstrapCommand command = command(demo());

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("SELECT name FROM teams ORDER BY name", String.class))
                .containsExactly("Команда А", "Команда Б");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM crm_user_profiles p JOIN teams t ON t.id = p.team_id
                WHERE t.name = 'Команда А' AND p.role = 'USER' AND p.active
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT t.name FROM crm_user_profiles p JOIN teams t ON t.id = p.team_id
                WHERE p.display_name = 'Руководитель Б' AND p.role = 'LEADER' AND p.active
                """, String.class)).isEqualTo("Команда Б");
        assertThat(jdbcTemplate.queryForList("""
                SELECT o.name, o.type, t.name AS team FROM organizations o JOIN teams t ON t.id = o.team_id
                WHERE o.type <> 'UNIVERSITY' ORDER BY o.name
                """)).extracting(row -> tuple(row.get("NAME"), row.get("TYPE"), row.get("TEAM")))
                .containsExactly(
                        tuple("Колледж связи (демо)", "COLLEGE", "Команда Б"),
                        tuple("Школа № 1 (демо)", "SCHOOL", "Команда А"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_user_profiles", Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agreements", Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForList("""
                SELECT o.name, COUNT(*) AS total FROM agreements a JOIN organizations o ON o.id = a.organization_id
                WHERE a.planned_on IS NOT NULL AND a.created_by = o.owner_manager_id GROUP BY o.name ORDER BY o.name
                """)).extracting(row -> tuple(row.get("NAME"), row.get("TOTAL")))
                .containsExactly(
                        tuple("Колледж связи (демо)", 1L),
                        tuple("Университет А", 2L),
                        tuple("Университет Б", 2L),
                        tuple("Школа № 1 (демо)", 1L));
        verify(interactionService, times(1)).create(any(), any(InteractionCreateRequest.class), anyString());
    }

    @Test
    void demoWorkGetsARiskAContractAndLicensesWithTransferMarksAndRepeatsWithoutDuplicates() {
        DemoBootstrapCommand command = command(demo());

        command.run(null);
        command.run(null);

        verify(interactionService, times(1)).updateFlags(any(), any(), any(InteractionFlagsRequest.class), anyString());
        verify(productAgreementService, times(2)).update(any(), any(), any(), any(ProductAgreementUpdateRequest.class), anyString());
        assertThat(jdbcTemplate.queryForList("SELECT risk_level, problem FROM interactions"))
                .extracting(row -> tuple(row.get("RISK_LEVEL"), row.get("PROBLEM")))
                .containsExactly(tuple(InteractionRiskLevel.MEDIUM.name(), "Вуз задерживает подписанный акт передачи"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT contract_number, license_expiry_year, transferred_kinds FROM product_agreements ORDER BY contract_number
                """)).extracting(row -> tuple(row.get("CONTRACT_NUMBER"), row.get("LICENSE_EXPIRY_YEAR"), row.get("TRANSFERRED_KINDS")))
                .containsExactly(tuple("ДЕМО-А/ЛС-1", 2026, 2), tuple("ДЕМО-А/ЛС-2", 2028, 1));
    }

    @Test
    void partnerRepresentsUniversityAWithSharedDemoDocumentsAndVisibleStepAndRepeatsWithoutDuplicates() {
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(new DemoBootstrapProperties.Identity("partner", ISSUER, "subject-partner", "Представитель вуза",
                UserRole.PARTNER, null, null, "Университет А", "Александра Демонстрационная"));
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT p.role, p.team_id, p.active, o.name AS organization, c.name AS contact
                FROM crm_user_profiles p
                JOIN organizations o ON o.id = p.partner_organization_id
                JOIN contacts c ON c.id = p.partner_contact_id
                WHERE p.subject = 'subject-partner'
                """))
                .containsEntry("ROLE", "PARTNER")
                .containsEntry("TEAM_ID", null)
                .containsEntry("ACTIVE", true)
                .containsEntry("ORGANIZATION", "Университет А")
                .containsEntry("CONTACT", "Александра Демонстрационная");
        verify(attachmentService, times(2)).upload(any(), any(), any(), any(), anyString());
        assertThat(jdbcTemplate.queryForList("SELECT original_name FROM attachments WHERE partner_visible ORDER BY original_name",
                String.class)).containsExactly("Демо: план сотрудничества.pdf", "Демо: рабочая программа.pdf");
        assertThat(jdbcTemplate.queryForObject("SELECT next_step_partner_visible FROM interactions", Boolean.class)).isTrue();

        identities.set(identities.size() - 1, new DemoBootstrapProperties.Identity("partner", ISSUER, "subject-partner",
                "Представитель вуза", UserRole.PARTNER, "team-a", null, "Университет А", "Александра Демонстрационная"));
        assertThatThrownBy(() -> command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null))
                .run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enrolmentOperatorJoinsTheOpenEnrolmentTeamOfTheMigrationAndCoursesOfTheSiteFixtureHavePrograms() {
        UUID openEnrolment = UUID.fromString("0e7e0000-0000-4000-8000-000000000001");
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, 'Открытый набор')", openEnrolment);
        List<DemoBootstrapProperties.Team> teams = new ArrayList<>(TEAMS);
        teams.add(new DemoBootstrapProperties.Team("open-enrolment", "Открытый набор"));
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(new DemoBootstrapProperties.Identity("enrol", ISSUER, "subject-enrol", "Оператор зачисления", UserRole.USER,
                "open-enrolment", true, null, null));
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, teams, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("SELECT display_name FROM crm_user_profiles WHERE enrolment_operator", String.class))
                .containsExactly("Оператор зачисления");
        assertThat(jdbcTemplate.queryForObject("SELECT team_id FROM crm_user_profiles WHERE display_name = 'Оператор зачисления'",
                UUID.class)).isEqualTo(openEnrolment);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.name FROM programs p JOIN directions d ON d.id = p.direction_id
                WHERE d.name = 'Демо-направление: курсы для физлиц' ORDER BY p.name
                """, String.class)).containsExactly("Инженер-тестировщик", "Промпт-инжиниринг");
    }

    @Test
    void bringsAPendingProfileFromAnEarlierLoginToTheConfiguredRoleTeamAndActivityAndRepeatsWithoutChanges() {
        DemoBootstrapProperties.Identity management = identity("management", "Руководство", UserRole.MANAGEMENT, null);
        new UserProfileRepository(jdbcClient).insertPendingIfAbsent(UUID.randomUUID(), ISSUER, management.subject(), "management", "management");
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(management);
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("""
                SELECT role, team_id, active, pending_activation FROM crm_user_profiles WHERE display_name = 'Руководство'
                """)).extracting(row -> tuple(row.get("ROLE"), row.get("TEAM_ID"), row.get("ACTIVE"), row.get("PENDING_ACTIVATION")))
                .containsExactly(tuple("MANAGEMENT", null, true, false));
    }

    @Test
    void bringsAnAlreadyActiveProfileWithAnotherRoleToTheConfiguredRoleTeamAndActivityAndRepeatsWithoutChanges() {
        DemoBootstrapProperties.Identity management = identity("management", "Руководство", UserRole.MANAGEMENT, null);
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, pending_activation)
                VALUES (?, ?, ?, 'Руководство', 'USER', NULL, TRUE, FALSE)
                """, UUID.randomUUID(), ISSUER, management.subject());
        List<DemoBootstrapProperties.Identity> identities = new ArrayList<>(DEMO_IDENTITIES);
        identities.add(management);
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(true, false, TEAMS, identities, DEMO_ORGANIZATIONS, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("""
                SELECT role, active, pending_activation, team_id FROM crm_user_profiles WHERE display_name = 'Руководство'
                """)).extracting(row -> tuple(row.get("ROLE"), row.get("ACTIVE"), row.get("PENDING_ACTIVATION"), row.get("TEAM_ID")))
                .containsExactly(tuple("MANAGEMENT", true, false, null));
    }

    @Test
    void renamesOnlyTeamsStillNamedByTheirKeyAndKeepsAdministratorChanges() {
        insertTeam("team-a", "team-a");
        insertTeam("team-b", "Продажи Сибирь");

        command(demo()).run(null);

        assertThat(jdbcTemplate.queryForList("SELECT name FROM teams ORDER BY name", String.class))
                .containsExactly("Команда А", "Продажи Сибирь");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM teams WHERE name = 'Команда А'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM teams WHERE name = 'Продажи Сибирь'", Integer.class)).isZero();
    }

    @Test
    void installationWithoutDemoDataCreatesOnlyOneAdministratorAndActivatesAnEarlyLogin() {
        DemoBootstrapProperties.Identity admin = identity("crm-admin", "Администратор CRM", UserRole.ADMIN, null);
        new UserProfileRepository(jdbcClient).insertPendingIfAbsent(UUID.randomUUID(), ISSUER, admin.subject(), "crm-admin", "crm-admin");
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(false, false, List.of(), List.of(admin), null, null));

        command.run(null);
        command.run(null);

        assertThat(jdbcTemplate.queryForList("SELECT display_name, role, team_id, active, pending_activation FROM crm_user_profiles"))
                .extracting(row -> tuple(row.get("DISPLAY_NAME"), row.get("ROLE"), row.get("TEAM_ID"), row.get("ACTIVE"),
                        row.get("PENDING_ACTIVATION")))
                .containsExactly(tuple("Администратор CRM", "ADMIN", null, true, false));
        for (String table : List.of("teams", "organizations", "directions", "programs", "vendors", "products", "interactions")) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        verifyNoInteractions(contactService, interactionService);
    }

    @Test
    void installationWithoutDemoDataRejectsDemoIdentitiesAndWritesNothing() {
        DemoBootstrapCommand command = command(new DemoBootstrapProperties(false, false, TEAMS, DEMO_IDENTITIES, null, null));

        assertThatThrownBy(() -> command.run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_user_profiles", Integer.class)).isZero();
        verify(interactionService, never()).create(any(), any(InteractionCreateRequest.class), anyString());
    }

    private DemoBootstrapCommand command(DemoBootstrapProperties properties) {
        return new DemoBootstrapCommand(properties, jdbcClient, new UserProfileRepository(jdbcClient), contactService,
                interactionService, attachmentService, productAgreementService);
    }

    private static DemoBootstrapProperties demo() {
        return new DemoBootstrapProperties(true, false, TEAMS, DEMO_IDENTITIES, DEMO_ORGANIZATIONS, null);
    }

    private static DemoBootstrapProperties.Identity identity(String key, String displayName, UserRole role, String teamKey) {
        return new DemoBootstrapProperties.Identity(key, ISSUER, "subject-" + key, displayName, role, teamKey, null, null, null);
    }

    private void insertTeam(String key, String name) {
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?)",
                UUID.nameUUIDFromBytes(("team:" + key).getBytes(StandardCharsets.UTF_8)), name);
    }

    private void createSchema() {
        for (String statement : List.of(
                """
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL UNIQUE, version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, login VARCHAR(200), role VARCHAR(16) NOT NULL, team_id UUID,
                    idp_enabled BOOLEAN NOT NULL DEFAULT TRUE, activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE,
                    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    access_revision INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (issuer, subject), CHECK (NOT (pending_activation AND active))
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL UNIQUE, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, concluded_on DATE,
                    valid_until DATE, planned_kind VARCHAR(16), planned_on DATE, planned_base_until DATE, parties VARCHAR(2000),
                    status VARCHAR(16) NOT NULL, version INTEGER NOT NULL DEFAULT 0, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (organization_id, number)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS directions (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, archived BOOLEAN NOT NULL DEFAULT FALSE,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS programs (
                    id UUID PRIMARY KEY, direction_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE, version INTEGER NOT NULL DEFAULT 0, UNIQUE (direction_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS vendors (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, archived BOOLEAN NOT NULL DEFAULT FALSE,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS products (
                    id UUID PRIMARY KEY, vendor_contact_id UUID, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE, version INTEGER NOT NULL DEFAULT 0, UNIQUE (vendor_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS vendor_contacts (
                    id UUID PRIMARY KEY, vendor_id UUID NOT NULL, name VARCHAR(200) NOT NULL, phone VARCHAR(16),
                    email VARCHAR(320), prefers_email BOOLEAN DEFAULT FALSE NOT NULL,
                    prefers_telegram BOOLEAN DEFAULT FALSE NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL,
                    personal_data_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, external_key VARCHAR(200) UNIQUE,
                    version INTEGER DEFAULT 0 NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (next_step_partner_visible BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, current_stage_id UUID,
                    version INTEGER NOT NULL DEFAULT 0, problem VARCHAR(1000), risk_level VARCHAR(16), risk_reason VARCHAR(1000),
                    next_action VARCHAR(500), created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, product_id UUID NOT NULL, contract_number VARCHAR(200),
                    license_expiry_year INTEGER, transferred_kinds INTEGER,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, name VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS attachments (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, original_name VARCHAR(255) NOT NULL,
                    partner_visible BOOLEAN DEFAULT FALSE NOT NULL
                )
                """
        )) {
            jdbcTemplate.execute(statement);
        }
    }
}
