package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.datasource.url=jdbc:h2:mem:signing-plan-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-signing-plan-http-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class SigningPlanHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID TEAM_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID USER_A = UUID.fromString("50000000-0000-0000-0000-000000000003");
    private static final UUID USER_B = UUID.fromString("50000000-0000-0000-0000-000000000004");
    private static final UUID MANAGEMENT = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID PARTNER = UUID.fromString("50000000-0000-0000-0000-000000000006");
    private static final UUID ORGANIZATION_A = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_B = UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID AGREEMENT_A = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID AGREEMENT_B = UUID.fromString("70000000-0000-0000-0000-000000000002");
    private static final String PLAN = "/api/reports/signing-plan";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : List.of("audit_events", "saved_reports", "command_idempotency_records", "agreements", "organizations",
                "crm_user_profiles", "teams")) {
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
        agreement(AGREEMENT_A, ORGANIZATION_A, "А-1", "DRAFT", "SIGNING", "2026-02-10");
        agreement(AGREEMENT_B, ORGANIZATION_B, "Б-1", "ACTIVE", "RENEWAL", "2026-03-15");
    }

    @Test
    void everyRoleGetsRowsOfItsOwnScopeAndPartnerIsForbidden() throws Exception {
        expectRows(USER_A, 1, "А-1");
        expectRows(USER_B, 1, "Б-1");
        expectRows(LEADER_A, 1, "А-1");
        expectRows(MANAGEMENT, 2, "А-1");
        expectRows(ADMIN, 0, null);
        mockMvc.perform(get(PLAN).param("year", "2026").param("quarter", "1").with(login(PARTNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(PLAN + "/file").param("format", "XLSX").with(login(PARTNER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PLAN)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidPeriodIsRejectedAndFilesAreServedAndJournalled() throws Exception {
        mockMvc.perform(get(PLAN).param("quarter", "5").with(login(MANAGEMENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.quarter").exists());
        mockMvc.perform(get(PLAN + "/file").param("format", "XLSX").param("year", "2026").param("quarter", "1").with(login(MANAGEMENT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mockMvc.perform(get(PLAN + "/file").param("format", "PDF").param("year", "2026").with(login(LEADER_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mockMvc.perform(get(PLAN + "/file").param("format", "JSON").with(login(LEADER_A)))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'REPORT_DOWNLOADED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void managementKeepsPersonalReportSettingsButCannotChangeBusinessData() throws Exception {
        ReportRequest definition = new ReportRequest(ReportKind.PORTFOLIO, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"),
                PeriodBasis.CREATED, ReportFilters.none(), List.of(ReportColumn.ORGANIZATION, ReportColumn.MANAGER), ReportFormat.XLSX,
                null, null);
        String body = objectMapper.writeValueAsString(new SavedReportRequest("Сводка руководства", definition, null, null));

        mockMvc.perform(post("/api/saved-reports")
                        .with(login(MANAGEMENT))
                        .with(csrf())
                        .header("Idempotency-Key", "saved-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Сводка руководства"));
        mockMvc.perform(get("/api/saved-reports").with(login(MANAGEMENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/saved-reports").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(patch("/api/agreements/{id}", AGREEMENT_A)
                        .with(login(MANAGEMENT))
                        .with(csrf())
                        .header("Idempotency-Key", "agreement-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"number\":\"А-1\",\"status\":\"DRAFT\",\"plannedKind\":\"SIGNING\",\"plannedOn\":\"2026-12-01\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/agreements/{id}", AGREEMENT_A)
                        .with(login(USER_B))
                        .with(csrf())
                        .header("Idempotency-Key", "agreement-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"number\":\"А-1\",\"status\":\"DRAFT\",\"plannedKind\":\"SIGNING\",\"plannedOn\":\"2026-12-01\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT planned_on FROM agreements WHERE id = ?", LocalDate.class, AGREEMENT_A))
                .isEqualTo(LocalDate.parse("2026-02-10"));

        mockMvc.perform(patch("/api/agreements/{id}", AGREEMENT_A)
                        .with(login(USER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "agreement-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"number\":\"А-1\",\"status\":\"DRAFT\",\"plannedKind\":\"SIGNING\",\"plannedOn\":\"2026-12-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedOn").value("2026-12-01"));
        assertThat(jdbcTemplate.queryForMap("SELECT action, request_id FROM audit_events WHERE action = 'AGREEMENT_PLAN_CHANGED'"))
                .containsKey("REQUEST_ID");
    }

    private void expectRows(UUID profile, int rows, String first) throws Exception {
        var result = mockMvc.perform(get(PLAN).param("year", "2026").param("quarter", "1").with(login(profile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(rows)));
        if (first != null) {
            result.andExpect(jsonPath("$.rows[0].agreementNumber").value(first));
        }
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
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
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
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
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
                    file_attachment_id UUID,
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
