package ru.rtk.crm.module;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:modules-off;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-modules-off-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.modules.enrolment.enabled=false",
        "app.modules.partner.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class ModulesOffHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID ADMIN = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID KAM = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID PARTNER = UUID.fromString("51000000-0000-0000-0000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, partner_organization_id UUID, partner_contact_id UUID,
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    access_revision INTEGER NOT NULL DEFAULT 0, enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE,
                    UNIQUE (issuer, subject)
                )
                """);
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        insertProfile(ADMIN, "Администратор", "ADMIN", null, false);
        insertProfile(KAM, "КАМ с флагом", "USER", null, true);
        insertProfile(PARTNER, "Представитель", "PARTNER", null, false);
    }

    @Test
    void configIsPublicAndReportsBothModulesOff() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules.enrolment").value(false))
                .andExpect(jsonPath("$.modules.partner").value(false));
    }

    @Test
    void everyPartnerMethodAnswersNotFoundForEveryRole() throws Exception {
        String id = UUID.randomUUID().toString();
        String contactId = UUID.randomUUID().toString();
        for (UUID profile : List.of(ADMIN, KAM, PARTNER)) {
            List<RequestBuilder> requests = List.of(
                    get("/api/partner/cabinet").with(login(profile)),
                    get("/api/partner/documents/{id}/download", id).with(login(profile)),
                    get("/api/organizations/{id}/partner-access", id).with(login(profile)),
                    post("/api/organizations/{id}/contacts/{contactId}/partner-access", id, contactId)
                            .with(login(profile)).with(csrf()),
                    delete("/api/organizations/{id}/contacts/{contactId}/partner-access", id, contactId)
                            .with(login(profile)).with(csrf())
            );
            for (RequestBuilder request : requests) {
                mockMvc.perform(request)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            }
        }
    }

    @Test
    void universityRepresentativeIsRejectedAtSignInAndEmployeesAreNotAffected() throws Exception {
        mockMvc.perform(get("/api/me").with(login(PARTNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PARTNER_MODULE_DISABLED"));
        mockMvc.perform(get("/api/me").with(login(KAM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enrolmentOperator").value(false));
    }

    @Test
    void learnerPrivacyMethodOfTheEnrolmentModuleAnswersNotFound() throws Exception {
        mockMvc.perform(post("/api/admin/personal-data/learners/{id}/restriction", UUID.randomUUID())
                        .with(login(ADMIN)).with(csrf()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"restricted\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void partnerVisibilityAndEnrolmentOperatorFlagsCannotBeTurnedOn() throws Exception {
        mockMvc.perform(patch("/api/attachments/{id}", UUID.randomUUID()).with(login(KAM)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"partnerVisible\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.partnerVisible").exists());
        mockMvc.perform(patch("/api/interactions/{id}", UUID.randomUUID()).with(login(KAM)).with(csrf())
                        .header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"nextStepPartnerVisible\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.nextStepPartnerVisible").exists());
        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", KAM).with(login(ADMIN)).with(csrf())
                        .header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"enrolmentOperator\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.enrolmentOperator").exists());
    }

    private void insertProfile(UUID id, String name, String role, UUID teamId, boolean operator) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, enrolment_operator)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, ISSUER, id.toString(), name, role, teamId, operator);
    }

    private RequestPostProcessor login(UUID profile) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profile.toString()));
    }
}
