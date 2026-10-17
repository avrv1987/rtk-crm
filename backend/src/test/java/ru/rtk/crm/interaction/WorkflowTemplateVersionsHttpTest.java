package ru.rtk.crm.interaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditJournalRepository;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:workflow-versions-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-workflow-versions-http-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class WorkflowTemplateVersionsHttpTest {
    private static final UUID TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID WORK_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final CrmProfile LEADER = new CrmProfile(
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            UserRole.LEADER,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            0
    );
    private static final String BODY = "{\"works\":[{\"id\":\"" + WORK_ID + "\",\"version\":4}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentProfileService currentProfileService;

    @MockitoBean
    private WorkflowRolloutService workflowRolloutService;

    @MockitoBean
    private AuditJournalRepository auditJournalRepository;

    @BeforeEach
    void setUp() {
        when(currentProfileService.requireActiveProfile(any())).thenReturn(LEADER);
    }

    @Test
    void versionsAreReadWithAuthorAndWorks() throws Exception {
        when(workflowRolloutService.versions(LEADER, TEMPLATE_ID)).thenReturn(List.of(new WorkflowTemplateVersion(
                2, "Маршрут", "Добавили подписание", "Вера Ковалёва", OffsetDateTime.parse("2026-10-17T10:00:00+03:00"),
                List.of(), List.of(), 5L
        )));

        mockMvc.perform(get("/api/workflow-templates/{id}/versions", TEMPLATE_ID).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(2))
                .andExpect(jsonPath("$[0].comment").value("Добавили подписание"))
                .andExpect(jsonPath("$[0].works").value(5));
    }

    @Test
    void rolloutNeedsCsrfAndPassesIdempotencyKey() throws Exception {
        when(workflowRolloutService.apply(eq(LEADER), eq(TEMPLATE_ID), eq(3), any(), eq("rollout-key")))
                .thenReturn(new WorkflowRolloutResult(3, 1));

        mockMvc.perform(post("/api/admin/workflow-templates/{id}/versions/3/rollout", TEMPLATE_ID)
                        .with(oidcLogin())
                        .header("Idempotency-Key", "rollout-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(workflowRolloutService);

        mockMvc.perform(post("/api/admin/workflow-templates/{id}/versions/3/rollout", TEMPLATE_ID)
                        .with(oidcLogin())
                        .with(csrf())
                        .header("Idempotency-Key", "rollout-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetVersion").value(3))
                .andExpect(jsonPath("$.updated").value(1));
        verify(workflowRolloutService).apply(
                eq(LEADER),
                eq(TEMPLATE_ID),
                eq(3),
                eq(new WorkflowRolloutRequest(null, List.of(new WorkflowRolloutWorkInput(WORK_ID, 4)), null)),
                eq("rollout-key")
        );
    }

    @Test
    void rolloutErrorsMapToForbiddenNotFoundAndConflict() throws Exception {
        when(workflowRolloutService.apply(eq(LEADER), eq(TEMPLATE_ID), eq(3), any(), eq("denied")))
                .thenThrow(new WorkflowTemplateAccessDeniedException());
        when(workflowRolloutService.apply(eq(LEADER), eq(TEMPLATE_ID), eq(3), any(), eq("stale")))
                .thenThrow(InteractionConflictException.version(5));
        when(workflowRolloutService.preview(eq(LEADER), eq(TEMPLATE_ID), eq(9), any()))
                .thenThrow(new WorkflowTemplateNotFoundException());

        mockMvc.perform(rollout("denied")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(rollout("stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(5));
        mockMvc.perform(post("/api/admin/workflow-templates/{id}/versions/9/rollout-preview", TEMPLATE_ID)
                        .with(oidcLogin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void partnerGetsForbiddenEverywhere() throws Exception {
        when(currentProfileService.requireActiveProfile(any())).thenThrow(new AdminCrmProfileAccessDeniedException());

        mockMvc.perform(get("/api/workflow-templates/{id}/versions", TEMPLATE_ID).with(oidcLogin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(rollout("partner")).andExpect(status().isForbidden());
        verifyNoInteractions(workflowRolloutService);
    }

    private org.springframework.test.web.servlet.RequestBuilder rollout(String key) {
        return post("/api/admin/workflow-templates/{id}/versions/3/rollout", TEMPLATE_ID)
                .with(oidcLogin())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }
}
