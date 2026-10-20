package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

// Веса индекса востребованности (М17): меняет только администратор, каждое изменение — в журнале, отчёт сразу
// считает по новым весам и называет их в примечании.
@org.springframework.context.annotation.Import(ru.rtk.crm.settings.TestSystemSettings.class)
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:demand-index-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-demand-index-http-test",
        "app.reports.storage-root=${java.io.tmpdir}/rtk-crm-demand-index-http-test-reports",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class DemandIndexSettingsHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID MANAGEMENT = UUID.fromString("50000000-0000-0000-0000-000000000015");
    private static final String SETTINGS = "/api/admin/demand-index/settings";
    private static final String WEIGHTS = """
            {"growthWeight":10,"completionWeight":40,"activityWeight":40,"universityWeight":10,"paymentsWeight":0,
             "minCoveragePercent":60,"smallBase":5}""";

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
        ReportTestData.createSchema(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM audit_events");
        ReportTestData.insertScenario(jdbcTemplate);
        ReportTestData.insertDemand(jdbcTemplate);
        jdbcTemplate.update("UPDATE crm_user_profiles SET issuer = ?, subject = CAST(id AS VARCHAR(512))", ISSUER);
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, access_revision)
                VALUES (?, ?, ?, 'Руководство', 'MANAGEMENT', NULL, TRUE, 0)
                """, MANAGEMENT, ISSUER, MANAGEMENT.toString());
    }

    @Test
    void onlyAdministratorChangesWeightsEveryChangeIsJournaledAndTheReportUsesThem() throws Exception {
        for (UUID profile : List.of(ReportTestData.MANAGER_A, ReportTestData.LEADER_A, MANAGEMENT)) {
            mockMvc.perform(get(SETTINGS).with(login(profile))).andExpect(status().isForbidden());
            mockMvc.perform(put(SETTINGS).with(login(profile)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(WEIGHTS))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(SETTINGS).with(login(profile)).with(csrf())).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());

        mockMvc.perform(get(SETTINGS).with(login(ReportTestData.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.current.growthWeight").value(30))
                .andExpect(jsonPath("$.defaults.minCoveragePercent").value(50));
        mockMvc.perform(put(SETTINGS).with(login(ReportTestData.ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(WEIGHTS.replace("\"paymentsWeight\":0", "\"paymentsWeight\":5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(put(SETTINGS).with(login(ReportTestData.ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"growthWeight\":100}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demand_index_settings", Integer.class)).isZero();

        mockMvc.perform(put(SETTINGS).with(login(ReportTestData.ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(WEIGHTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.current.completionWeight").value(40))
                .andExpect(jsonPath("$.updatedByName").value("Дмитрий Администратор"));
        mockMvc.perform(post("/api/reports/preview").with(login(ReportTestData.LEADER_A)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"DEMAND\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].id", hasItem("DEMAND_RANK")))
                .andExpect(jsonPath("$.notes[*]", hasItem(containsString("веса: прирост записанных 10, доля завершивших 40"))));

        mockMvc.perform(delete(SETTINGS).with(login(ReportTestData.ADMIN)).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForList(
                "SELECT details FROM audit_events WHERE action = 'DEMAND_INDEX_SETTINGS_CHANGED' ORDER BY occurred_at", String.class))
                .containsExactly(
                        "вес «Прирост записанных»: 30 → 10; вес «Доля завершивших»: 25 → 40; вес «Активность за 7 дней»: 25 → 40;"
                                + " вес «Оплаты открытого набора»: 10 → 0; порог покрытия: 50 → 60 %; малая база: 10 → 5 участий",
                        "восстановлены значения по умолчанию; вес «Прирост записанных»: 10 → 30; вес «Доля завершивших»: 40 → 25;"
                                + " вес «Активность за 7 дней»: 40 → 25; вес «Оплаты открытого набора»: 0 → 10; порог покрытия:"
                                + " 60 → 50 %; малая база: 5 → 10 участий");
        mockMvc.perform(get(SETTINGS).with(login(ReportTestData.ADMIN)))
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.current.growthWeight").value(30));
    }

    private RequestPostProcessor login(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profileId.toString()));
    }
}
