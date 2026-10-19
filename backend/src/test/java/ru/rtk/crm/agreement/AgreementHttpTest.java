package ru.rtk.crm.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:agreement-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=target/rtk-crm-agreement-http-test",
        "app.attachments.scanner-port=1",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class AgreementHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID TEAM_A = UUID.fromString("41000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("41000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID USER_A = UUID.fromString("51000000-0000-0000-0000-000000000003");
    private static final UUID USER_B = UUID.fromString("51000000-0000-0000-0000-000000000004");
    private static final UUID MANAGEMENT = UUID.fromString("51000000-0000-0000-0000-000000000005");
    private static final UUID PARTNER = UUID.fromString("51000000-0000-0000-0000-000000000006");
    private static final UUID ORGANIZATION_A = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_B = UUID.fromString("61000000-0000-0000-0000-000000000002");
    private static final UUID AGREEMENT_A = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID AGREEMENT_B = UUID.fromString("71000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        AgreementTestSchema.create(jdbcTemplate);
        AgreementTestSchema.createWorkTables(jdbcTemplate);
        AgreementTestSchema.clear(jdbcTemplate);
        for (String table : List.of("audit_events", "command_idempotency_records", "agreements", "organizations", "crm_user_profiles",
                "teams")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", TEAM_A, "Команда А", TEAM_B, "Команда Б");
        profile(ADMIN, "Администратор", "ADMIN", null);
        profile(LEADER_A, "Руководитель А", "LEADER", TEAM_A);
        profile(USER_A, "КАМ А", "USER", TEAM_A);
        profile(USER_B, "КАМ Б", "USER", TEAM_B);
        profile(MANAGEMENT, "Руководство", "MANAGEMENT", null);
        profile(PARTNER, "Представитель вуза", "PARTNER", null);
        organization(ORGANIZATION_A, "Университет А", TEAM_A, USER_A);
        organization(ORGANIZATION_B, "Университет Б", TEAM_B, USER_B);
        agreement(AGREEMENT_A, ORGANIZATION_A, "А-1", "DRAFT", "SIGNING", "2026-12-10");
        agreement(AGREEMENT_B, ORGANIZATION_B, "Б-1", "ACTIVE", "RENEWAL", "2026-12-15");
    }

    @Test
    void registryFollowsTheScopeAndIsClosedToAdministratorAndPartner() throws Exception {
        mockMvc.perform(get("/api/agreement-registry").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].number").value("А-1"))
                .andExpect(jsonPath("$[0].managerName").value("КАМ А"));
        mockMvc.perform(get("/api/agreement-registry").param("status", "ACTIVE").param("status", "DRAFT").with(login(MANAGEMENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/agreement-registry").param("status", "ACTIVE").with(login(LEADER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/agreement-registry").with(login(ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/agreement-registry").with(login(PARTNER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/agreement-registry").param("reviewLongerThanDays", "0").with(login(USER_A)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/agreement-registry/file").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mockMvc.perform(get("/api/reports/agreements-period/file").param("format", "PDF").with(login(MANAGEMENT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mockMvc.perform(get("/api/reports/agreements-period").with(login(ADMIN))).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'REPORT_DOWNLOADED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void transitionNeedsCsrfAnEditorAndTheVisibleUniversity() throws Exception {
        String body = "{\"version\":0,\"toStatus\":\"REVIEW\"}";
        mockMvc.perform(post("/api/agreements/{id}/transitions", AGREEMENT_A).with(login(USER_A))
                        .header("Idempotency-Key", "t-0").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(post("/api/agreements/{id}/transitions", AGREEMENT_A).with(login(MANAGEMENT)).with(csrf())
                        .header("Idempotency-Key", "t-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agreements/{id}/transitions", AGREEMENT_A).with(login(USER_B)).with(csrf())
                        .header("Idempotency-Key", "t-2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agreements/{id}/transitions", AGREEMENT_A).with(login(PARTNER)).with(csrf())
                        .header("Idempotency-Key", "t-3").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agreements/{id}/transitions", AGREEMENT_A).with(login(USER_A)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/agreements/{id}/transitions", AGREEMENT_A).with(login(LEADER_A)).with(csrf())
                        .header("Idempotency-Key", "t-4").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW"))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.transitions[?(@.toStatus == 'DRAFT')].commentRequired").value(true));
        mockMvc.perform(post("/api/agreements/{id}/transitions", AGREEMENT_A).with(login(USER_A)).with(csrf())
                        .header("Idempotency-Key", "t-5").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1));
    }

    @Test
    void fileVersionIsUploadedOnlyByEditorsAndAnUncheckedFileIsNotDownloaded() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "договор.pdf", "application/pdf",
                "%PDF-1.4 demo".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/agreements/{id}/files", AGREEMENT_A).file(file).param("comment", "Редакция вуза")
                        .with(login(MANAGEMENT)).with(csrf()).header("Idempotency-Key", "f-1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/agreements/{id}/files", AGREEMENT_A).file(file).with(login(USER_A))
                        .header("Idempotency-Key", "f-2"))
                .andExpect(status().isForbidden());
        String response = mockMvc.perform(multipart("/api/agreements/{id}/files", AGREEMENT_A).file(file)
                        .param("comment", "Редакция вуза").with(login(USER_A)).with(csrf()).header("Idempotency-Key", "f-3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.comment").value("Редакция вуза"))
                .andExpect(jsonPath("$.status").value("UNVERIFIABLE"))
                .andExpect(jsonPath("$.downloadable").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String fileId = response.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
        mockMvc.perform(get("/api/agreement-files/{id}/download", fileId).with(login(USER_A))).andExpect(status().isNotFound());
        jdbcTemplate.update("UPDATE agreement_files SET status = 'CLEAN' WHERE id = ?", UUID.fromString(fileId));
        mockMvc.perform(get("/api/agreement-files/{id}/download", fileId).with(login(USER_B))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agreement-files/{id}/download", fileId).with(login(MANAGEMENT)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'AGREEMENT_FILE_DOWNLOADED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void workCardGetsItsContractsAndStageRoles() throws Exception {
        UUID work = UUID.fromString("81000000-0000-0000-0000-000000000001");
        jdbcTemplate.update("INSERT INTO interactions (id, organization_id, title) VALUES (?, ?, 'Работа А')", work, ORGANIZATION_A);
        jdbcTemplate.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name, template_stage_id) VALUES"
                        + " (?, ?, 1, 'Обмен документами', NULL), (?, ?, 2, 'Подписание договора', ?)",
                UUID.randomUUID(), work, UUID.randomUUID(), work, UUID.fromString("00000000-0000-0000-0000-000000000816"));
        ru.rtk.crm.interaction.StagePropertiesTestSchema.markLegacyStages(jdbcTemplate);
        jdbcTemplate.update("INSERT INTO agreement_interactions (agreement_id, interaction_id) VALUES (?, ?)", AGREEMENT_A, work);

        mockMvc.perform(get("/api/interactions/{id}/agreements", work).with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingRuleEnabled").value(true))
                .andExpect(jsonPath("$.stages[1].role").value("SIGNING"))
                .andExpect(jsonPath("$.agreements[0].number").value("А-1"));
        mockMvc.perform(get("/api/interactions/{id}/agreements", work).with(login(USER_B))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/interactions/{id}/agreements", work).with(login(ADMIN))).andExpect(status().isNotFound());
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
                """, id, name, teamId, ownerManagerId, OffsetDateTime.parse("2026-09-24T09:00:00+00:00"));
    }

    private void agreement(UUID id, UUID organizationId, String number, String status, String kind, String plannedOn) {
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, planned_kind, planned_on, planned_base_until, status, created_by,
                    created_at, updated_at, concluded_on)
                VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, DATE '2026-01-15')
                """, id, organizationId, number, kind, LocalDate.parse(plannedOn), status, ADMIN,
                OffsetDateTime.parse("2026-01-01T09:00:00+00:00"), OffsetDateTime.parse("2026-01-01T09:00:00+00:00"));
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY,
                    name VARCHAR(160) NOT NULL UNIQUE,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    archived BOOLEAN DEFAULT FALSE NOT NULL,
                    default_workflow_template_id UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, 
                    partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL,
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
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
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
                    UNIQUE (command_id, organization_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    decision_role VARCHAR(32), primary_contact BOOLEAN DEFAULT FALSE NOT NULL, inactive BOOLEAN DEFAULT FALSE NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID,
                    id UUID PRIMARY KEY,
                    personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
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
                CREATE TABLE IF NOT EXISTS saved_reports (
                    id UUID PRIMARY KEY,
                    owner_profile_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    definition_json TEXT NOT NULL,
                    period_preset VARCHAR(32),
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    number VARCHAR(100) NOT NULL,
                    concluded_on DATE,
                    valid_until DATE,
                    planned_kind VARCHAR(16),
                    planned_on DATE,
                    planned_base_until DATE,
                    parties VARCHAR(2000),
                    status VARCHAR(16) NOT NULL,
                    sent_on DATE,
                    status_changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    university_signer_id UUID,
                    school_signer_id UUID,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT agreements_organization_number_key UNIQUE (organization_id, number)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activities (
                    id UUID PRIMARY KEY,
                    agreement_id UUID NOT NULL,
                    kind_id UUID NOT NULL,
                    title VARCHAR(300) NOT NULL,
                    unit VARCHAR(50),
                    planned_volume INTEGER,
                    actual_volume INTEGER,
                    planned_start DATE,
                    planned_end DATE,
                    actual_start DATE,
                    actual_end DATE,
                    responsible_profile_id UUID,
                    status VARCHAR(16) NOT NULL,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_kinds (
                    id UUID PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    sort_order INTEGER NOT NULL,
                    archived BOOLEAN NOT NULL DEFAULT FALSE,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }
}
