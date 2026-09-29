package ru.rtk.crm.catalog;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:organization-history-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-organization-history-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class OrganizationHistoryHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID TEAM_A = UUID.fromString("42000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("42000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID KAM_A = UUID.fromString("52000000-0000-0000-0000-000000000003");
    private static final UUID KAM_OTHER = UUID.fromString("52000000-0000-0000-0000-000000000004");
    private static final UUID LEADER_B = UUID.fromString("52000000-0000-0000-0000-000000000005");
    private static final UUID MANAGEMENT = UUID.fromString("52000000-0000-0000-0000-000000000006");
    private static final UUID PARTNER = UUID.fromString("52000000-0000-0000-0000-000000000007");
    private static final UUID ORGANIZATION_A = UUID.fromString("62000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_B = UUID.fromString("62000000-0000-0000-0000-000000000002");
    private static final UUID CONTACT_A = UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final UUID WORK_A = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID WORK_B = UUID.fromString("82000000-0000-0000-0000-000000000002");
    private static final UUID STAGE = UUID.fromString("92000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of("interaction_event_contacts", "contact_events", "organization_assignment_events",
                "interaction_events", "interactions", "contacts", "organizations", "crm_user_profiles", "teams")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", TEAM_A, "Команда А", TEAM_B, "Команда Б");
        profile(ADMIN, "Администратор", "ADMIN", null);
        profile(LEADER_A, "Руководитель А", "LEADER", TEAM_A);
        profile(KAM_A, "Ирина КАМ", "USER", TEAM_A);
        profile(KAM_OTHER, "КАМ без вуза", "USER", TEAM_A);
        profile(LEADER_B, "Руководитель Б", "LEADER", TEAM_B);
        profile(MANAGEMENT, "Руководство", "MANAGEMENT", null);
        profile(PARTNER, "Представитель вуза", "PARTNER", null);
        organization(ORGANIZATION_A, "Университет А", TEAM_A, KAM_A);
        organization(ORGANIZATION_B, "Университет Б", TEAM_B, null);
        jdbcTemplate.update("""
                INSERT INTO contacts (id, organization_id, name, version, created_by, created_at, updated_at)
                VALUES (?, ?, 'Проректор Демонстрационный', 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CONTACT_A, ORGANIZATION_A, KAM_A);
        work(WORK_A, ORGANIZATION_A, "Внедрение платформы");
        work(WORK_B, ORGANIZATION_B, "Чужая работа");
        workEvent(WORK_A, "CREATED", null, "Встреча", null, "2026-09-10T10:00:00+03:00");
        workEvent(WORK_A, "TRANSITIONED", "Встреча", "Договор", "Согласовали сроки", "2026-09-12T10:00:00+03:00");
        workEvent(WORK_A, "COMMENTED", "Договор", "Договор", "Ждём подпись", "2026-09-13T23:30:00+03:00");
        workEvent(WORK_B, "COMMENTED", "Договор", "Договор", "Секрет другой команды", "2026-09-13T12:00:00+03:00");
        jdbcTemplate.update("""
                INSERT INTO organization_assignment_events (
                    id, organization_id, command_id, previous_owner_manager_id, previous_owner_manager_display_name,
                    owner_manager_id, new_owner_manager_display_name, actor_profile_id, actor_display_name, request_id,
                    handover_note, version, occurred_at
                ) VALUES (?, ?, ?, NULL, NULL, ?, 'Ирина КАМ', ?, 'Руководитель А', 'request-1', 'Передаём вуз с открытыми договорами', 1, ?)
                """, UUID.randomUUID(), ORGANIZATION_A, UUID.randomUUID(), KAM_A, LEADER_A,
                OffsetDateTime.parse("2026-09-11T09:00:00+03:00"));
        jdbcTemplate.update("""
                INSERT INTO contact_events (id, contact_id, command_id, actor_profile_id, changes, version, occurred_at)
                VALUES (?, ?, ?, ?, ?, 1, ?)
                """, UUID.randomUUID(), CONTACT_A, UUID.randomUUID(), KAM_A,
                "[{\"field\":\"phone\",\"previousValue\":null,\"value\":\"+7 900 000-00-00\"}]",
                OffsetDateTime.parse("2026-09-14T09:00:00+03:00"));
    }

    @Test
    void feedMergesWorkAssignmentAndContactEventsNewestFirst() throws Exception {
        mockMvc.perform(get(url(ORGANIZATION_A)).with(login(KAM_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].kind").value("CONTACT"))
                .andExpect(jsonPath("$.items[0].contactName").value("Проректор Демонстрационный"))
                .andExpect(jsonPath("$.items[0].changes[0].field").value("phone"))
                .andExpect(jsonPath("$.items[1].kind").value("COMMENTED"))
                .andExpect(jsonPath("$.items[1].comment").value("Ждём подпись"))
                .andExpect(jsonPath("$.items[1].interactionId").value(WORK_A.toString()))
                .andExpect(jsonPath("$.items[1].interactionTitle").value("Внедрение платформы"))
                .andExpect(jsonPath("$.items[2].kind").value("TRANSITIONED"))
                .andExpect(jsonPath("$.items[2].fromStageName").value("Встреча"))
                .andExpect(jsonPath("$.items[2].stageName").value("Договор"))
                .andExpect(jsonPath("$.items[3].kind").value("ASSIGNMENT"))
                .andExpect(jsonPath("$.items[3].description").value("Назначен ответственный: Ирина КАМ."))
                .andExpect(jsonPath("$.items[3].comment").value("Передаём вуз с открытыми договорами"))
                .andExpect(jsonPath("$.items[3].actorName").value("Руководитель А"))
                .andExpect(jsonPath("$.items[4].kind").value("CREATED"));
    }

    @Test
    void everyRoleSeesOnlyItsOwnScope() throws Exception {
        expectTotal(KAM_A, ORGANIZATION_A, 5);
        expectTotal(LEADER_A, ORGANIZATION_A, 5);
        expectTotal(MANAGEMENT, ORGANIZATION_A, 5);
        expectTotal(MANAGEMENT, ORGANIZATION_B, 1);
        expectTotal(LEADER_B, ORGANIZATION_B, 1);
        for (UUID profile : List.of(KAM_OTHER, LEADER_B)) {
            mockMvc.perform(get(url(ORGANIZATION_A)).with(login(profile)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mockMvc.perform(get(url(ORGANIZATION_B)).with(login(KAM_A))).andExpect(status().isNotFound());
        mockMvc.perform(get(url(ORGANIZATION_A)).with(login(ADMIN))).andExpect(status().isNotFound());
        mockMvc.perform(get(url(ORGANIZATION_A)).with(login(PARTNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(url(ORGANIZATION_A))).andExpect(status().isUnauthorized());
    }

    @Test
    void kindFilterKeepsOnlyChosenKinds() throws Exception {
        mockMvc.perform(get(url(ORGANIZATION_A)).param("kinds", "ASSIGNMENT").with(login(LEADER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("ASSIGNMENT"));
        mockMvc.perform(get(url(ORGANIZATION_A)).param("kinds", "TRANSITIONED,CONTACT").with(login(LEADER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("CONTACT"))
                .andExpect(jsonPath("$.items[1].kind").value("TRANSITIONED"));
        mockMvc.perform(get(url(ORGANIZATION_A)).param("kinds", "COMMENTED").param("kinds", "CREATED").with(login(LEADER_A)))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void periodIsCountedInMoscowDaysIncludingTheLastDay() throws Exception {
        mockMvc.perform(get(url(ORGANIZATION_A)).param("from", "2026-09-12").param("to", "2026-09-13").with(login(KAM_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("COMMENTED"))
                .andExpect(jsonPath("$.items[1].kind").value("TRANSITIONED"));
        mockMvc.perform(get(url(ORGANIZATION_A)).param("from", "2026-09-14").with(login(KAM_A)))
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get(url(ORGANIZATION_A)).param("to", "2026-09-10").with(login(KAM_A)))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void pagesAreLoadedOneAfterAnother() throws Exception {
        mockMvc.perform(get(url(ORGANIZATION_A)).param("size", "2").with(login(KAM_A)))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].kind").value("CONTACT"));
        mockMvc.perform(get(url(ORGANIZATION_A)).param("size", "2").param("page", "2").with(login(KAM_A)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind").value("CREATED"));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        mockMvc.perform(get(url(ORGANIZATION_A)).param("kinds", "UNKNOWN").with(login(KAM_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.kinds").exists());
        mockMvc.perform(get(url(ORGANIZATION_A)).param("from", "13.09.2026").with(login(KAM_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.from").exists());
        mockMvc.perform(get(url(ORGANIZATION_A)).param("from", "2026-09-14").param("to", "2026-09-13").with(login(KAM_A)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(url(ORGANIZATION_A)).param("size", "101").with(login(KAM_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.size").exists());
    }

    private void expectTotal(UUID profile, UUID organization, int total) throws Exception {
        mockMvc.perform(get(url(organization)).with(login(profile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total));
    }

    private String url(UUID organizationId) {
        return "/api/organizations/" + organizationId + "/history";
    }

    private RequestPostProcessor login(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profileId.toString()));
    }

    private void profile(UUID id, String displayName, String role, UUID teamId) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, pending_activation)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, FALSE)
                """, id, ISSUER, id.toString(), displayName, role, teamId);
    }

    private void organization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, 'UNIVERSITY', ?, ?, 0, ?)
                """, id, name, teamId, ownerManagerId, OffsetDateTime.parse("2026-09-01T09:00:00+00:00"));
    }

    private void work(UUID id, UUID organizationId, String title) {
        jdbcTemplate.update("INSERT INTO interactions (id, organization_id, title) VALUES (?, ?, ?)", id, organizationId, title);
    }

    private void workEvent(UUID workId, String type, String from, String stage, String comment, String at) {
        jdbcTemplate.update("""
                INSERT INTO interaction_events (
                    id, interaction_id, type, stage_id, stage_name_snapshot, from_stage_name_snapshot, comment,
                    actor_profile_id, version, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, UUID.randomUUID(), workId, type, STAGE, stage == null ? "Встреча" : stage, from, comment, KAM_A,
                OffsetDateTime.parse(at));
    }

    private void createSchema() {
        String[] statements = {
                """
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
                    archived BOOLEAN DEFAULT FALSE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, login VARCHAR(200), role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    activation_requested_at TIMESTAMP WITH TIME ZONE, anonymized_at TIMESTAMP WITH TIME ZONE,
                    access_revision INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
                    partner_organization_id UUID, partner_contact_id UUID UNIQUE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (issuer, subject)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, version INTEGER NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    ended_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, command_id UUID NOT NULL,
                    previous_owner_manager_id UUID, previous_owner_manager_display_name VARCHAR(200),
                    owner_manager_id UUID, new_owner_manager_display_name VARCHAR(200),
                    actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL, request_id VARCHAR(64) NOT NULL,
                    reason VARCHAR(32), handover_note VARCHAR(2000), version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, name VARCHAR(200) NOT NULL, position VARCHAR(200),
                    email VARCHAR(320), phone VARCHAR(50), version INTEGER NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    decision_role VARCHAR(32), primary_contact BOOLEAN DEFAULT FALSE NOT NULL, inactive BOOLEAN DEFAULT FALSE NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID, personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contact_events (
                    id UUID PRIMARY KEY, contact_id UUID NOT NULL, command_id UUID NOT NULL, actor_profile_id UUID NOT NULL,
                    changes VARCHAR(10000) NOT NULL, version INTEGER NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_issues (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_events (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, type VARCHAR(32) NOT NULL, stage_id UUID,
                    stage_name_snapshot VARCHAR(200) NOT NULL, from_stage_name_snapshot VARCHAR(200), comment TEXT,
                    actor_profile_id UUID NOT NULL, owner_manager_id_snapshot UUID, version INTEGER NOT NULL DEFAULT 0,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_event_contacts (
                    event_id UUID NOT NULL, contact_id UUID NOT NULL, change_type VARCHAR(16) NOT NULL,
                    PRIMARY KEY (event_id, contact_id)
                )
                """
        };
        for (String statement : statements) {
            jdbcTemplate.execute(statement);
        }
    }
}
