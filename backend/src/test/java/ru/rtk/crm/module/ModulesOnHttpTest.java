package ru.rtk.crm.module;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "spring.datasource.url=jdbc:h2:mem:modules-on;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-modules-on-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.modules.enrolment.enabled=true",
        "app.modules.partner.enabled=true",
        "app.enrolment.active-key-version=v1",
        "app.enrolment.keys.v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.enrolment.fingerprint-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class ModulesOnHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID KAM = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID OPERATOR = UUID.fromString("51000000-0000-0000-0000-000000000004");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, partner_organization_id UUID, partner_contact_id UUID,
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    access_revision INTEGER NOT NULL DEFAULT 0, enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE,
                    UNIQUE (issuer, subject)
                )
                """);
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        insertProfile(KAM, "КАМ", false);
        insertProfile(OPERATOR, "Оператор", true);
    }

    @Test
    void configReportsBothModulesOn() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules.enrolment").value(true))
                .andExpect(jsonPath("$.modules.partner").value(true));
    }

    @Test
    void moduleMethodsAreReachableAndStillGuardedByTheirRoles() throws Exception {
        mockMvc.perform(get("/api/partner/cabinet").with(login(KAM)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/enrolment/streams").with(login(KAM)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/me").with(login(OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolmentOperator").value(true));
    }

    private void insertProfile(UUID id, String name, boolean operator) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, enrolment_operator)
                VALUES (?, ?, ?, ?, 'USER', NULL, ?)
                """, id, ISSUER, id.toString(), name, operator);
    }

    private RequestPostProcessor login(UUID profile) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profile.toString()));
    }
}
