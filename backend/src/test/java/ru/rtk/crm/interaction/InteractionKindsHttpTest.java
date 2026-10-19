package ru.rtk.crm.interaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKind;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRequest;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:interaction-kinds-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-interaction-kinds-http-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class InteractionKindsHttpTest {
    private static final UUID KIND = UUID.fromString("00000000-0000-0000-0000-000000000851");
    private static final CrmProfile KAM = new CrmProfile(UUID.fromString("00000000-0000-0000-0000-000000000011"), UserRole.USER,
            UUID.fromString("00000000-0000-0000-0000-000000000001"), 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentProfileService currentProfileService;

    @MockitoBean
    private InteractionKindService interactionKindService;

    @MockitoBean
    private AuditJournalRepository auditJournalRepository;

    @BeforeEach
    void setUp() {
        when(currentProfileService.requireActiveProfile(any())).thenReturn(KAM);
    }

    @Test
    void changesNeedCsrfAndForeignRolesGetForbidden() throws Exception {
        String body = "{\"name\":\"Повышение квалификации\",\"active\":true}";
        mockMvc.perform(post("/api/admin/interaction-kinds").with(oidcLogin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(interactionKindService);

        when(interactionKindService.create(eq(KAM), any(), eq("kind-key"))).thenThrow(new AdminCrmProfileAccessDeniedException());
        mockMvc.perform(post("/api/admin/interaction-kinds").with(oidcLogin()).with(csrf()).header("Idempotency-Key", "kind-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(interactionKindService).create(KAM, new InteractionKindRequest(null, "Повышение квалификации", null, true, null, null),
                "kind-key");

        when(interactionKindService.managed(KAM)).thenThrow(new WorkflowTemplateAccessDeniedException());
        mockMvc.perform(get("/api/admin/interaction-kinds").with(oidcLogin())).andExpect(status().isForbidden());
    }

    @Test
    void availableKindsMissingKindAndBadIdentifiers() throws Exception {
        when(interactionKindService.available(KAM)).thenReturn(java.util.List.of(new InteractionKind(
                KIND, "Взаимодействие по ИТ-программе", null, true, null, null, 0, 0, null, null)));
        mockMvc.perform(get("/api/interaction-kinds").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Взаимодействие по ИТ-программе"));

        when(interactionKindService.update(eq(KAM), eq(KIND), any(), eq("missing"))).thenThrow(new InteractionKindNotFoundException());
        mockMvc.perform(patch("/api/admin/interaction-kinds/{id}", KIND).with(oidcLogin()).with(csrf())
                        .header("Idempotency-Key", "missing").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"x\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/interaction-kinds/resolution").with(oidcLogin()).param("organizationId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.organizationId").exists());
        mockMvc.perform(delete("/api/admin/interaction-kind-rules/{id}", KIND).with(oidcLogin()).with(csrf())
                        .header("Idempotency-Key", "delete").param("version", "2"))
                .andExpect(status().isNoContent());
        verify(interactionKindService).deleteRule(KAM, KIND, 2, "delete");
    }
}
