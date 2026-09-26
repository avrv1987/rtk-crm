package ru.rtk.crm.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:access-management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-access-management-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class AccessManagementHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID TEAM_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID USER_A = UUID.fromString("50000000-0000-0000-0000-000000000003");
    private static final UUID PENDING = UUID.fromString("50000000-0000-0000-0000-000000000004");
    private static final UUID ORGANIZATION_A = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_B = UUID.fromString("60000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        jdbcTemplate.update("DELETE FROM crm_profile_events");
        jdbcTemplate.update("DELETE FROM organization_assignment_events");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM command_idempotency_records");
        jdbcTemplate.update("DELETE FROM organizations");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        jdbcTemplate.update("DELETE FROM teams");
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", TEAM_A, "Команда А", TEAM_B, "Команда Б");
        insertProfile(ADMIN, "Администратор", "ADMIN", null, true, false);
        insertProfile(LEADER_A, "Руководитель А", "LEADER", TEAM_A, true, false);
        insertProfile(USER_A, "КАМ А", "USER", TEAM_A, true, false);
        insertProfile(PENDING, "Новый сотрудник", "USER", null, false, true);
        insertOrganization(ORGANIZATION_A, "Университет А", TEAM_A, USER_A);
        insertOrganization(ORGANIZATION_B, "Университет Б", TEAM_B, null);
    }

    @Test
    void leaderCreatesContactInOwnTeamAndForeignOrganizationIsHidden() throws Exception {
        mockMvc.perform(post("/api/organizations/{id}/contacts", ORGANIZATION_A)
                        .with(login(LEADER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "leader-contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ирина Куратор\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(LEADER_A.toString()));
        mockMvc.perform(post("/api/organizations/{id}/contacts", ORGANIZATION_B)
                        .with(login(LEADER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "leader-foreign-contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Чужой контакт\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void administratorTeamChangeAppliesToTheNextRequestOfTheChangedProfile() throws Exception {
        mockMvc.perform(get("/api/organizations/{id}", ORGANIZATION_A).with(login(USER_A)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", USER_A)
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "move-user-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"teamId\":\"" + TEAM_B + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamName").value("Команда Б"))
                .andExpect(jsonPath("$.accessRevision").value(1));

        mockMvc.perform(get("/api/me").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamName").value("Команда Б"))
                .andExpect(jsonPath("$.accessRevision").value(1));
        mockMvc.perform(get("/api/organizations/{id}", ORGANIZATION_A).with(login(USER_A)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", ADMIN)
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "self-role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").exists());
    }

    @Test
    void unknownTeamIsReportedInRussian() throws Exception {
        mockMvc.perform(patch("/api/admin/teams/{id}", UUID.randomUUID())
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "unknown-team")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Команда Икс\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Команда не найдена"));
    }

    @Test
    void pendingProfileHasNoAccessUntilActivation() throws Exception {
        mockMvc.perform(get("/api/me").with(login(PENDING)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CRM_PROFILE_PENDING"))
                .andExpect(jsonPath("$.message").value("Профиль CRM ожидает активации администратором"));
        mockMvc.perform(get("/api/organizations").with(login(PENDING)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CRM_PROFILE_PENDING"));
        mockMvc.perform(get("/api/admin/crm-profiles").param("pending", "true").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(PENDING.toString()));
    }

    private RequestPostProcessor login(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profileId.toString()));
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId, boolean active, boolean pending) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, pending_activation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ISSUER, id.toString(), displayName, role, teamId, active, pending);
    }

    private void insertOrganization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, 'UNIVERSITY', ?, ?, 0, ?)
                """, id, name, teamId, ownerManagerId, OffsetDateTime.parse("2026-09-24T09:00:00+00:00"));
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY,
                    name VARCHAR(160) NOT NULL UNIQUE,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY,
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
                    UNIQUE (issuer, subject)
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
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    position VARCHAR(200),
                    email VARCHAR(320),
                    phone VARCHAR(50),
                    version INTEGER NOT NULL,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
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
                    UNIQUE (command_id, organization_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_profile_events (
                    id UUID PRIMARY KEY,
                    profile_id UUID NOT NULL,
                    command_id UUID NOT NULL,
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
    }
}
