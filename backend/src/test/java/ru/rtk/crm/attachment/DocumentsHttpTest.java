package ru.rtk.crm.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.ProductAgreementService;
import ru.rtk.crm.interaction.ProductAgreementUpdateRequest;
import ru.rtk.crm.interaction.ProductTransferKind;
import ru.rtk.crm.interaction.ProductTransferStatus;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:documents-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-documents-http-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
class DocumentsHttpTest {
    private static final UUID INTERACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID ATTACHMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID AGREEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final CrmProfile PROFILE = new CrmProfile(
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            UserRole.USER,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            0
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentProfileService currentProfileService;

    @MockitoBean
    private AttachmentService attachmentService;

    @MockitoBean
    private ProductAgreementService productAgreementService;

    @MockitoBean
    private AuditJournalRepository auditJournalRepository;

    @BeforeEach
    void setUp() {
        when(currentProfileService.requireActiveProfile(any())).thenReturn(PROFILE);
    }

    @Test
    void previewIsInlineWithRestrictivePolicyAndWithoutCaching() throws Exception {
        Attachment attachment = new Attachment(ATTACHMENT_ID, INTERACTION_ID, UUID.randomUUID(), null, "скан лицензии.pdf",
                "application/pdf", 4, AttachmentStatus.CLEAN, AttachmentKind.SIGNED_SCAN, 1, null, PROFILE.id(), 0,
                OffsetDateTime.parse("2026-09-25T10:00:00+03:00"));
        when(attachmentService.preview(PROFILE, ATTACHMENT_ID)).thenReturn(new AttachmentService.DownloadedAttachment(
                attachment,
                new ByteArrayInputStream("%PDF".getBytes())
        ));

        mockMvc.perform(get("/api/attachments/{id}/preview", ATTACHMENT_ID).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        verify(auditJournalRepository)
                .recordAttachmentAccess(eq(AuditAction.ATTACHMENT_PREVIEWED), eq(PROFILE.id()), eq(ATTACHMENT_ID), any());
    }

    @Test
    void deletionByNonAuthorIsForbiddenWithReadableReason() throws Exception {
        when(attachmentService.delete(eq(PROFILE), eq(INTERACTION_ID), eq(ATTACHMENT_ID), any(), eq("delete-key")))
                .thenThrow(new AttachmentDeletionForbiddenException());

        mockMvc.perform(post("/api/interactions/{id}/attachments/{attachmentId}/deletion", INTERACTION_ID, ATTACHMENT_ID)
                        .with(oidcLogin())
                        .with(csrf())
                        .header("Idempotency-Key", "delete-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"reason\":\"ошибочный файл\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Удалить документ может автор или руководитель команды"));
    }

    @Test
    void agreementUpdateParsesContractAndTransferMarks() throws Exception {
        mockMvc.perform(patch("/api/interactions/{id}/product-agreements/{agreementId}", INTERACTION_ID, AGREEMENT_ID)
                        .with(oidcLogin())
                        .with(csrf())
                        .header("Idempotency-Key", "agreement-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":2,
                                 "contract":{"contractNumber":"007/2026","licenseSigned":true,"licenseExpiryYear":2027},
                                 "transfers":[{"kind":"MATERIALS","status":"TRANSFERRED","transferredOn":"2026-09-20"}]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductAgreementUpdateRequest> request = ArgumentCaptor.forClass(ProductAgreementUpdateRequest.class);
        verify(productAgreementService).update(eq(PROFILE), eq(INTERACTION_ID), eq(AGREEMENT_ID), request.capture(),
                eq("agreement-key"));
        assertThat(request.getValue().version()).isEqualTo(2);
        assertThat(request.getValue().contract().contractNumber()).isEqualTo("007/2026");
        assertThat(request.getValue().transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.kind()).isEqualTo(ProductTransferKind.MATERIALS);
            assertThat(transfer.status()).isEqualTo(ProductTransferStatus.TRANSFERRED);
            assertThat(transfer.transferredOn()).isEqualTo(LocalDate.parse("2026-09-20"));
        });
    }

    @Test
    void workListRejectsLicenseYearOutsideTheAllowedRange() throws Exception {
        mockMvc.perform(get("/api/interactions").with(oidcLogin()).param("licenseExpiresBy", "1900"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.licenseExpiresBy").exists());
    }
}
