package ru.rtk.crm.report;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.List;
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

@org.springframework.context.annotation.Import(ru.rtk.crm.settings.TestSystemSettings.class)
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:labor-market-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-labor-market-http-test",
        "app.reports.storage-root=${java.io.tmpdir}/rtk-crm-labor-market-http-test-reports",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.labor-market.base-url=http://labor-market.test",
        "app.labor-market.token=env-labor-token-value"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class LaborMarketHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID MANAGEMENT = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID PARTNER = UUID.fromString("50000000-0000-0000-0000-000000000006");
    private static final String SETTINGS = "/api/admin/labor-market/settings";
    private static final String MAPPINGS = "/api/admin/labor-market/mappings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, partner_organization_id UUID,
                    partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, id UUID PRIMARY KEY,
                    login VARCHAR(200), idp_enabled BOOLEAN NOT NULL DEFAULT TRUE, activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE, issuer VARCHAR(512), subject VARCHAR(512),
                    display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID, active BOOLEAN NOT NULL DEFAULT TRUE,
                    pending_activation BOOLEAN NOT NULL DEFAULT FALSE, access_revision INTEGER NOT NULL DEFAULT 0,
                    version INTEGER NOT NULL DEFAULT 0, updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL UNIQUE, version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    archived BOOLEAN DEFAULT FALSE NOT NULL, default_workflow_template_id UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY, category VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL, actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL, object_type VARCHAR(32), object_id UUID, object_name VARCHAR(500),
                    details VARCHAR(2000), request_id VARCHAR(64), occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS labor_market_settings (
                    id SMALLINT PRIMARY KEY, base_url VARCHAR(500), token_encrypted VARCHAR(2000),
                    token_changed_at TIMESTAMP WITH TIME ZONE, updated_by UUID NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        ReportTestData.createSchema(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM audit_events");
        jdbcTemplate.update("DELETE FROM labor_market_settings");
        ReportTestData.insertScenario(jdbcTemplate);
        ReportTestData.insertDemand(jdbcTemplate);
        jdbcTemplate.update("UPDATE crm_user_profiles SET issuer = ?, subject = CAST(id AS VARCHAR(512))", ISSUER);
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, access_revision)
                VALUES (?, ?, ?, 'Руководство', 'MANAGEMENT', NULL, TRUE, 0), (?, ?, ?, 'Представитель вуза', 'PARTNER', NULL, TRUE, 0)
                """, MANAGEMENT, ISSUER, MANAGEMENT.toString(), PARTNER, ISSUER, PARTNER.toString());
        ReportTestData.trackMarket(jdbcTemplate, ReportTestData.DIRECTION, "programming");
        ReportTestData.insertMarket(jdbcTemplate, "programming", YearMonth.now(ReportRequest.ZONE).minusMonths(1), 200, "Тестовый источник");
        ReportTestData.insertMarket(jdbcTemplate, "programming", YearMonth.now(ReportRequest.ZONE), 250, "Тестовый источник");
    }

    @Test
    void sourceSettingsAndMappingsAreOnlyForAdministrator() throws Exception {
        UUID direction = ReportTestData.DIRECTION;
        for (UUID profile : List.of(ReportTestData.MANAGER_A, ReportTestData.LEADER_A, MANAGEMENT, PARTNER)) {
            mockMvc.perform(get(SETTINGS).with(login(profile))).andExpect(status().isForbidden());
            mockMvc.perform(put(SETTINGS).with(login(profile)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"baseUrl\":\"https://market.example.test\"}")).andExpect(status().isForbidden());
            mockMvc.perform(delete(SETTINGS).with(login(profile)).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/labor-market/check").with(login(profile)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
            mockMvc.perform(get(MAPPINGS).with(login(profile))).andExpect(status().isForbidden());
            mockMvc.perform(put(MAPPINGS + "/" + direction).with(login(profile)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"externalCode\":\"programming\"}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/sources/LABOR_MARKET/sync").with(login(profile)).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
        assertCount("labor_market_settings", 0);
        assertCount("labor_market_mappings WHERE version = 0", 1);

        mockMvc.perform(get(SETTINGS).with(login(ReportTestData.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://labor-market.test"))
                .andExpect(jsonPath("$.token.origin").value("ENVIRONMENT"))
                .andExpect(content().string(not(containsString("env-labor-token-value"))));
        mockMvc.perform(put(SETTINGS).with(login(ReportTestData.ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://market.example.test\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(MAPPINGS).with(login(ReportTestData.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directions", hasSize(1)))
                .andExpect(jsonPath("$.directions[0].directionName").value("Программирование"))
                .andExpect(jsonPath("$.directions[0].externalCode").value("programming"))
                .andExpect(jsonPath("$.directions[0].latestVacancies").value(250))
                .andExpect(jsonPath("$.sourceDirections[0].code").value("programming"));
        mockMvc.perform(put(MAPPINGS + "/" + direction).with(login(ReportTestData.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalCode\":\"missing\",\"version\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(MAPPINGS + "/" + direction).with(login(ReportTestData.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalCode\":\"programming\",\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void everyRoleWithReportAccessSeesMarketColumnsAndPartnerDoesNot() throws Exception {
        String body = "{\"kind\":\"DEMAND\"}";
        for (UUID profile : List.of(ReportTestData.MANAGER_A, ReportTestData.LEADER_A, MANAGEMENT)) {
            var result = mockMvc.perform(post("/api/reports/preview").with(login(profile)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns[*].title", hasItem("Спрос рынка: вакансии")))
                    .andExpect(jsonPath("$.columns[*].title", hasItem("Тренд спроса, %")));
            if (profile != MANAGEMENT) {
                result.andExpect(jsonPath("$.items[?(@.PROGRAM == 'Java-разработчик')].MARKET_VACANCIES", hasItem(250)))
                        .andExpect(jsonPath("$.items[?(@.PROGRAM == 'Java-разработчик')].MARKET_TREND", hasItem(25)))
                        .andExpect(jsonPath("$.notes[*]", hasItem(containsString("Тестовый источник"))));
            }
        }
        mockMvc.perform(post("/api/reports/preview").with(login(PARTNER)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reports").with(login(PARTNER)).with(csrf()).header("Idempotency-Key", "market-partner")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"DEMAND\",\"format\":\"XLSX\"}"))
                .andExpect(status().isForbidden());
    }

    private void assertCount(String tableAndCondition, int expected) {
        org.assertj.core.api.Assertions.assertThat(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableAndCondition, Integer.class)).isEqualTo(expected);
    }

    private RequestPostProcessor login(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profileId.toString()));
    }
}
