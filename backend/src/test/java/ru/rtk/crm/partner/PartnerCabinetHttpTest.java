package ru.rtk.crm.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import ru.rtk.crm.access.AccountSyncException;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.access.KeycloakAccountConflictException;
import ru.rtk.crm.catalog.CatalogArchivedEvent;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:partner-cabinet;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-partner-cabinet-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
@ExtendWith(OutputCaptureExtension.class)
class PartnerCabinetHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID TEAM_A = UUID.fromString("41000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("41000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID KAM_A = UUID.fromString("51000000-0000-0000-0000-000000000003");
    private static final UUID KAM_OTHER = UUID.fromString("51000000-0000-0000-0000-000000000004");
    private static final UUID LEADER_B = UUID.fromString("51000000-0000-0000-0000-000000000005");
    private static final UUID MANAGEMENT = UUID.fromString("51000000-0000-0000-0000-000000000006");
    private static final UUID PARTNER_A = UUID.fromString("51000000-0000-0000-0000-000000000007");
    private static final UUID PARTNER_B = UUID.fromString("51000000-0000-0000-0000-000000000008");
    private static final UUID ORGANIZATION_A = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_B = UUID.fromString("61000000-0000-0000-0000-000000000002");
    private static final UUID RECTOR = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID COORDINATOR = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID CONTACT_B = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID WORK_A = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID WORK_A_HIDDEN_STEP = UUID.fromString("81000000-0000-0000-0000-000000000002");
    private static final UUID WORK_B = UUID.fromString("81000000-0000-0000-0000-000000000003");
    private static final UUID SHARED_DOCUMENT = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID INTERNAL_DOCUMENT = UUID.fromString("91000000-0000-0000-0000-000000000002");
    private static final UUID QUARANTINED_DOCUMENT = UUID.fromString("91000000-0000-0000-0000-000000000003");
    private static final UUID FOREIGN_DOCUMENT = UUID.fromString("91000000-0000-0000-0000-000000000004");
    private static final UUID SHARED_STORAGE_KEY = UUID.fromString("a1000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private KeycloakAccountClient keycloakAccountClient;

    @BeforeEach
    void setUp() throws IOException {
        createSchema();
        for (String table : new String[] {
                "audit_events", "crm_profile_events", "contact_events", "agreement_activities", "agreements", "attachments",
                "product_agreements", "products", "interaction_stage_completions", "interaction_events", "interaction_issues", "interactions",
                "interaction_stages", "programs", "command_idempotency_records", "crm_user_profiles", "contacts", "organizations",
                "teams"
        }) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", TEAM_A, "Команда А", TEAM_B, "Команда Б");
        insertProfile(ADMIN, "Администратор", "ADMIN", null);
        insertProfile(LEADER_A, "Руководитель А", "LEADER", TEAM_A);
        insertProfile(KAM_A, "Ирина КАМ", "USER", TEAM_A);
        insertProfile(KAM_OTHER, "КАМ без вуза", "USER", TEAM_A);
        insertProfile(LEADER_B, "Руководитель Б", "LEADER", TEAM_B);
        insertProfile(MANAGEMENT, "Руководство", "MANAGEMENT", null);
        insertOrganization(ORGANIZATION_A, "Университет А", TEAM_A, KAM_A);
        insertOrganization(ORGANIZATION_B, "Университет Б", TEAM_B, null);
        insertContact(RECTOR, ORGANIZATION_A, "Проректор Демонстрационный", "Rector@Example.test");
        insertContact(COORDINATOR, ORGANIZATION_A, "Координатор Тестовый", null);
        insertContact(CONTACT_B, ORGANIZATION_B, "Контакт Б", null);
        insertPartner(PARTNER_A, "Координатор Тестовый", ORGANIZATION_A, COORDINATOR);
        insertPartner(PARTNER_B, "Контакт Б", ORGANIZATION_B, CONTACT_B);
        seedCabinet();
        Path storage = Path.of(System.getProperty("java.io.tmpdir"), "rtk-crm-partner-cabinet-test");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve(SHARED_STORAGE_KEY.toString()), "%PDF-1.4 план", StandardCharsets.UTF_8);
    }

    @Test
    void partnerSeesOnlyOwnOrganizationSharedDocumentsAndFlaggedStep() throws Exception {
        mockMvc.perform(get("/api/me").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PARTNER"));

        String body = mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.organization.name").value("Университет А"))
                .andExpect(jsonPath("$.organization.type").value("UNIVERSITY"))
                .andExpect(jsonPath("$.manager.name").value("Ирина КАМ"))
                .andExpect(jsonPath("$.works", hasSize(2)))
                .andExpect(jsonPath("$.documents", hasSize(2)))
                .andExpect(jsonPath("$.agreements", hasSize(1)))
                .andExpect(jsonPath("$.agreements[0].subject[0]").value("Стажировки студентов"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode cabinet = objectMapper.readTree(body);
        JsonNode work = findById(cabinet.path("works"), WORK_A);
        assertThat(work.path("programName").asText()).isEqualTo("Цифровая кафедра");
        assertThat(work.path("productNames").get(0).asText()).isEqualTo("Облачная платформа");
        assertThat(work.path("currentStageName").asText()).isEqualTo("Подписание");
        assertThat(work.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(work.path("passedStages")).hasSize(2);
        assertThat(work.path("passedStages").get(0).path("name").asText()).isEqualTo("Встреча");
        assertThat(work.path("passedStages").get(0).path("passedOn").asText()).isEqualTo("2026-09-10");
        assertThat(work.path("passedStages").get(1).path("passedOn").asText()).isEqualTo("2026-09-15");
        assertThat(work.path("nextStep").path("action").asText()).isEqualTo("Прислать список преподавателей");
        assertThat(findById(cabinet.path("works"), WORK_A_HIDDEN_STEP).path("nextStep").isNull()).isTrue();
        assertThat(findById(cabinet.path("documents"), SHARED_DOCUMENT).path("downloadable").asBoolean()).isTrue();
        assertThat(findById(cabinet.path("documents"), QUARANTINED_DOCUMENT).path("downloadable").asBoolean()).isFalse();
        assertThat(body)
                .doesNotContain("Университет Б", "Внутренняя справка", "Внутренний шаг", "Работа Б", "Проректор Демонстрационный",
                        "внутренний комментарий");

        mockMvc.perform(get("/api/partner/documents/{id}/download", SHARED_DOCUMENT).with(partnerLogin(PARTNER_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().string(containsString("%PDF")));
        for (UUID hidden : new UUID[] {INTERNAL_DOCUMENT, QUARANTINED_DOCUMENT, FOREIGN_DOCUMENT, UUID.randomUUID()}) {
            mockMvc.perform(get("/api/partner/documents/{id}/download", hidden).with(partnerLogin(PARTNER_A)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'ATTACHMENT_DOWNLOADED' AND actor_profile_id = ?",
                Integer.class, PARTNER_A)).isEqualTo(1);

        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.name").value("Университет Б"))
                .andExpect(jsonPath("$.manager").doesNotExist())
                .andExpect(jsonPath("$.works[0].nextStep").doesNotExist());
    }

    @Test
    void partnerCannotUseStaffApiOrWriteAnything() throws Exception {
        mockMvc.perform(get("/api/organizations").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/organizations/{id}/contacts", ORGANIZATION_A).with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/interactions/{id}/events", WORK_A).with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/attachments/{id}/download", INTERNAL_DOCUMENT).with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/organizations/{id}/partner-access", ORGANIZATION_A).with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/attachments/{id}", INTERNAL_DOCUMENT)
                        .with(partnerLogin(PARTNER_A)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"partnerVisible\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/organizations/{id}/contacts", ORGANIZATION_A)
                        .with(partnerLogin(PARTNER_A)).with(csrf())
                        .header("Idempotency-Key", "partner-contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Новый контакт\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(partnerLogin(PARTNER_A)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/partner/cabinet").with(login(KAM_A)))
                .andExpect(status().isForbidden());
        for (String path : new String[] {"/api/issues", "/api/issues/file", "/api/interactions/" + WORK_A + "/issues"}) {
            mockMvc.perform(get(path).with(partnerLogin(PARTNER_A))).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/interactions/{id}/issues", WORK_A)
                        .with(partnerLogin(PARTNER_A)).with(csrf())
                        .header("Idempotency-Key", "partner-issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"kind\":\"PROBLEM\",\"description\":\"Вуз\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/interactions/{id}/issues", WORK_A)
                        .with(login(KAM_A))
                        .header("Idempotency-Key", "kam-issue-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"kind\":\"PROBLEM\",\"description\":\"Без CSRF\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_issues WHERE interaction_id = ?", Integer.class,
                WORK_A)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT partner_visible FROM attachments WHERE id = ?", Boolean.class,
                INTERNAL_DOCUMENT)).isFalse();
    }

    @Test
    void closedArchivedOrUnboundPartnerLosesTheCabinetAtOnce() throws Exception {
        jdbcTemplate.update("UPDATE contacts SET inactive = TRUE WHERE id = ?", COORDINATOR);
        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("закрыт")));
        jdbcTemplate.update("UPDATE contacts SET inactive = FALSE WHERE id = ?", COORDINATOR);
        jdbcTemplate.update("UPDATE organizations SET status = 'ARCHIVED' WHERE id = ?", ORGANIZATION_A);
        mockMvc.perform(get("/api/partner/documents/{id}/download", SHARED_DOCUMENT).with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden());
        jdbcTemplate.update("UPDATE organizations SET status = 'ACTIVE' WHERE id = ?", ORGANIZATION_A);
        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", PARTNER_A);
        mockMvc.perform(get("/api/me").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CRM_PROFILE_REQUIRED"));
    }

    @Test
    void responsibleKamOpensAccessWithOneTimePasswordAndClosesIt(CapturedOutput output) throws Exception {
        when(keycloakAccountClient.createPartnerUser(
                eq("rector@example.test"), eq("rector@example.test"), eq("Проректор Демонстрационный"), eq("Университет А"), anyString()
        )).thenReturn("kc-rector");

        String body = mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(login(KAM_A)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.login").value("rector@example.test"))
                .andExpect(jsonPath("$.access.contactName").value("Проректор Демонстрационный"))
                .andExpect(jsonPath("$.access.active").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String password = objectMapper.readTree(body).path("temporaryPassword").asText();
        assertThat(password).hasSize(16).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*");
        verify(keycloakAccountClient).createPartnerUser(
                eq("rector@example.test"), eq("rector@example.test"), eq("Проректор Демонстрационный"), eq("Университет А"),
                eq(password)
        );

        UUID profileId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_user_profiles WHERE partner_contact_id = ?", UUID.class, RECTOR);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT role, team_id, issuer, subject, partner_organization_id FROM crm_user_profiles WHERE id = ?", profileId))
                .containsEntry("ROLE", "PARTNER")
                .containsEntry("TEAM_ID", null)
                .containsEntry("ISSUER", ISSUER)
                .containsEntry("SUBJECT", "kc-rector")
                .containsEntry("PARTNER_ORGANIZATION_ID", ORGANIZATION_A);
        String accessList = mockMvc.perform(get("/api/organizations/{id}/partner-access", ORGANIZATION_A).with(login(KAM_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(accessList).doesNotContain(password);
        mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(login(KAM_A)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTNER_ACCESS_EXISTS"));

        mockMvc.perform(delete("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(login(KAM_A)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.accountSyncRequired").value(false));
        verify(keycloakAccountClient).setEnabled("kc-rector", false);

        when(keycloakAccountClient.createPartnerUser(anyString(), isNull(), anyString(), anyString(), anyString()))
                .thenThrow(new KeycloakAccountConflictException());
        mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_B, CONTACT_B)
                        .with(login(ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTNER_ACCESS_EXISTS"));
        UUID newcomer = UUID.randomUUID();
        insertContact(newcomer, ORGANIZATION_B, "Новый контакт Б", null);
        mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_B, newcomer)
                        .with(login(ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTNER_ACCOUNT_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_user_profiles WHERE partner_contact_id = ?", Integer.class, newcomer)).isZero();

        String reopened = mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(login(LEADER_A)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.access.profileId").value(profileId.toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String secondPassword = objectMapper.readTree(reopened).path("temporaryPassword").asText();
        assertThat(secondPassword).isNotEqualTo(password);
        verify(keycloakAccountClient).enableWithTemporaryPassword("kc-rector", secondPassword);

        assertThat(jdbcTemplate.queryForList(
                "SELECT previous_active, active, role FROM crm_profile_events WHERE profile_id = ? ORDER BY version", profileId))
                .hasSize(3);
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events ORDER BY occurred_at", String.class))
                .containsExactly("ACCOUNT_CREATED", "ACCOUNT_DISABLED", "ACCOUNT_ENABLED");
        String journal = String.join(" ", jdbcTemplate.queryForList(
                "SELECT COALESCE(details, '') FROM audit_events", String.class));
        assertThat(journal).contains("временный пароль выдан").doesNotContain(password, secondPassword);
        assertThat(output.getOut()).doesNotContain(password).doesNotContain(secondPassword);
    }

    @Test
    void onlyResponsibleKamLeaderAndAdministratorManageAccessAndManagementOnlyReads() throws Exception {
        mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(login(KAM_OTHER)).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(login(LEADER_B)).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/{id}/partner-access", ORGANIZATION_A).with(login(LEADER_B)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations/{id}/partner-access", ORGANIZATION_A).with(login(MANAGEMENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].contactName").value("Координатор Тестовый"))
                .andExpect(jsonPath("$[0].active").value(true));
        mockMvc.perform(post("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, RECTOR)
                        .with(login(MANAGEMENT)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("ответственный КАМ")));
        mockMvc.perform(delete("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, COORDINATOR)
                        .with(login(MANAGEMENT)).with(csrf()))
                .andExpect(status().isForbidden());
        verify(keycloakAccountClient, never()).createPartnerUser(anyString(), anyString(), anyString(), anyString(), anyString());

        doThrow(new AccountSyncException("Keycloak недоступен")).when(keycloakAccountClient).setEnabled("kc-" + PARTNER_B, false);
        mockMvc.perform(delete("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_B, CONTACT_B)
                        .with(login(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.accountSyncRequired").value(true))
                .andExpect(jsonPath("$.accountSyncError").value("Keycloak недоступен"));
        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_B)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", PARTNER_A)
                        .with(login(ADMIN)).with(csrf())
                        .header("Idempotency-Key", "partner-role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"role\":\"USER\",\"teamId\":\"" + TEAM_A + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").value(containsString("карточке вуза")));
        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", KAM_OTHER)
                        .with(login(ADMIN)).with(csrf())
                        .header("Idempotency-Key", "make-partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"role\":\"PARTNER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archivingContactOrOrganizationClosesPartnerAccess() throws Exception {
        String contact = mockMvc.perform(get("/api/organizations/{id}/contacts", ORGANIZATION_A).with(login(KAM_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(contact).contains("Координатор Тестовый");
        mockMvc.perform(patch("/api/organizations/{id}/contacts/{contactId}", ORGANIZATION_A, COORDINATOR)
                        .with(login(KAM_A)).with(csrf())
                        .header("Idempotency-Key", "archive-coordinator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Координатор Тестовый\",\"primary\":false,\"inactive\":true,\"confirm\":false}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM crm_user_profiles WHERE id = ?", Boolean.class, PARTNER_A))
                .isFalse();
        verify(keycloakAccountClient).setEnabled("kc-" + PARTNER_A, false);

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CatalogArchivedEvent(ORGANIZATION_B, null, LEADER_B, "archive-b")));
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM crm_user_profiles WHERE id = ?", Boolean.class, PARTNER_B))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_profile_events WHERE active = FALSE AND previous_active = TRUE", Integer.class))
                .isEqualTo(2);
        mockMvc.perform(get("/api/organizations/{id}/partner-access", ORGANIZATION_A).with(login(KAM_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(content().string(not(containsString("temporaryPassword"))));
    }

    private JsonNode findById(JsonNode items, UUID id) {
        for (JsonNode item : items) {
            if (item.path("id").asText().equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("Item " + id + " is missing");
    }

    private RequestPostProcessor login(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profileId.toString()));
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, pending_activation)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, FALSE)
                """, id, ISSUER, id.toString(), displayName, role, teamId);
    }

    private void insertPartner(UUID id, String displayName, UUID organizationId, UUID contactId) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (
                    id, issuer, subject, display_name, login, role, team_id, active, pending_activation,
                    partner_organization_id, partner_contact_id
                ) VALUES (?, ?, ?, ?, ?, 'PARTNER', NULL, TRUE, FALSE, ?, ?)
                """, id, ISSUER, "kc-" + id, displayName, "vuz-" + id.toString().substring(34), organizationId, contactId);
    }

    private RequestPostProcessor partnerLogin(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("kc-" + profileId));
    }

    private void insertOrganization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, 'UNIVERSITY', ?, ?, 0, ?)
                """, id, name, teamId, ownerManagerId, OffsetDateTime.parse("2026-09-24T09:00:00+00:00"));
    }

    private void insertContact(UUID id, UUID organizationId, String name, String email) {
        jdbcTemplate.update("""
                INSERT INTO contacts (id, organization_id, name, email, version, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, organizationId, name, email, KAM_A);
    }

    private void seedCabinet() {
        UUID program = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO programs (id, name) VALUES (?, ?)", program, "Цифровая кафедра");
        jdbcTemplate.update("INSERT INTO products (id, name) VALUES (?, ?)", product, "Облачная платформа");
        UUID[] stages = insertWork(WORK_A, ORGANIZATION_A, "Внедрение платформы", program, "Прислать список преподавателей", true, 2);
        jdbcTemplate.update("""
                INSERT INTO interaction_events (id, interaction_id, type, from_stage_id, occurred_at)
                VALUES (?, ?, 'TRANSITIONED', ?, ?), (?, ?, 'COMMENTED', NULL, ?)
                """, UUID.randomUUID(), WORK_A, stages[0], OffsetDateTime.parse("2026-09-10T12:00:00+03:00"),
                UUID.randomUUID(), WORK_A, OffsetDateTime.parse("2026-09-11T12:00:00+03:00"));
        jdbcTemplate.update("""
                INSERT INTO interaction_stage_completions (interaction_id, stage_id, completed_on) VALUES (?, ?, ?)
                """, WORK_A, stages[1], java.time.LocalDate.parse("2026-09-15"));
        jdbcTemplate.update("INSERT INTO product_agreements (id, interaction_id, product_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), WORK_A, product);
        insertWork(WORK_A_HIDDEN_STEP, ORGANIZATION_A, "Повышение квалификации", null, "Внутренний шаг", false, 0);
        insertWork(WORK_B, ORGANIZATION_B, "Работа Б", null, "Шаг Б", false, 0);
        insertDocument(SHARED_DOCUMENT, WORK_A, "План сотрудничества.pdf", "CLEAN", true, SHARED_STORAGE_KEY);
        insertDocument(INTERNAL_DOCUMENT, WORK_A, "Внутренняя справка.pdf", "CLEAN", false, UUID.randomUUID());
        insertDocument(QUARANTINED_DOCUMENT, WORK_A, "Проект договора.pdf", "QUARANTINE", true, UUID.randomUUID());
        insertDocument(FOREIGN_DOCUMENT, WORK_B, "Документ вуза Б.pdf", "CLEAN", true, UUID.randomUUID());
        UUID agreementA = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, concluded_on, valid_until, status)
                VALUES (?, ?, 'С-1/2026', DATE '2026-01-15', DATE '2028-12-31', 'ACTIVE'),
                       (?, ?, 'С-Б', NULL, NULL, 'DRAFT')
                """, agreementA, ORGANIZATION_A, UUID.randomUUID(), ORGANIZATION_B);
        jdbcTemplate.update("""
                INSERT INTO agreement_activities (id, agreement_id, title, status) VALUES (?, ?, 'Стажировки студентов', 'PLANNED'),
                (?, ?, 'Отменённое мероприятие', 'CANCELLED')
                """, UUID.randomUUID(), agreementA, UUID.randomUUID(), agreementA);
    }

    private UUID[] insertWork(
            UUID id,
            UUID organizationId,
            String title,
            UUID programId,
            String nextAction,
            boolean nextStepVisible,
            int currentIndex
    ) {
        String[] names = {"Встреча", "Обмен документами", "Подписание"};
        UUID[] stages = new UUID[names.length];
        for (int index = 0; index < names.length; index++) {
            stages[index] = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name) VALUES (?, ?, ?, ?)",
                    stages[index], id, index, names[index]);
        }
        jdbcTemplate.update("""
                INSERT INTO interactions (
                    id, organization_id, title, current_stage_id, next_action, next_action_at, program_id, work_status,
                    next_step_partner_visible, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP)
                """, id, organizationId, title, stages[currentIndex], nextAction,
                OffsetDateTime.parse("2026-10-01T10:00:00+03:00"), programId, nextStepVisible);
        jdbcTemplate.update("""
                INSERT INTO interaction_issues (
                    id, interaction_id, kind, description, risk_level, responsible_profile_id, created_by, created_at
                ) VALUES (?, ?, 'RISK', 'внутренний комментарий', 'HIGH', ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID());
        return stages;
    }

    private void insertDocument(UUID id, UUID interactionId, String name, String status, boolean partnerVisible, UUID storageKey) {
        jdbcTemplate.update("""
                INSERT INTO attachments (
                    id, interaction_id, original_name, media_type, size_bytes, storage_key, status, kind, partner_visible, created_at
                ) VALUES (?, ?, ?, 'application/pdf', 18, ?, ?, 'OTHER', ?, CURRENT_TIMESTAMP)
                """, id, interactionId, name, storageKey, status, partnerVisible);
    }

    private void createSchema() {
        String[] statements = {
                """
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
                    archived BOOLEAN DEFAULT FALSE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (
                    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, login VARCHAR(200), role VARCHAR(16) NOT NULL, team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    activation_requested_at TIMESTAMP WITH TIME ZONE, anonymized_at TIMESTAMP WITH TIME ZONE,
                    access_revision INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
                    partner_organization_id UUID, partner_contact_id UUID UNIQUE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (issuer, subject)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL, type VARCHAR(16) NOT NULL, team_id UUID NOT NULL,
                    owner_manager_id UUID, version INTEGER NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    ended_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, previous_owner_manager_id UUID, owner_manager_id UUID,
                    occurred_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL, result_json VARCHAR(10000),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, name VARCHAR(200) NOT NULL, position VARCHAR(200),
                    email VARCHAR(320), phone VARCHAR(50), version INTEGER NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    decision_role VARCHAR(32), primary_contact BOOLEAN DEFAULT FALSE NOT NULL, inactive BOOLEAN DEFAULT FALSE NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID, personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS contact_events (
                    id UUID PRIMARY KEY, contact_id UUID NOT NULL, command_id UUID NOT NULL, actor_profile_id UUID NOT NULL,
                    changes VARCHAR(10000) NOT NULL, version INTEGER NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_profile_events (
                    id UUID PRIMARY KEY, profile_id UUID NOT NULL, command_id UUID NOT NULL UNIQUE, actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL, previous_display_name VARCHAR(200) NOT NULL,
                    display_name VARCHAR(200) NOT NULL, previous_role VARCHAR(16) NOT NULL, role VARCHAR(16) NOT NULL,
                    previous_team_id UUID, team_id UUID, previous_active BOOLEAN NOT NULL, active BOOLEAN NOT NULL,
                    previous_enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL,
                    request_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY, category VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL, actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL, object_type VARCHAR(32), object_id UUID, object_name VARCHAR(500),
                    details VARCHAR(2000), request_id VARCHAR(64), occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                "CREATE TABLE IF NOT EXISTS programs (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS products (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL)",
                """
                CREATE TABLE IF NOT EXISTS interaction_issues (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, current_stage_id UUID NOT NULL,
                    next_action VARCHAR(500), next_action_at TIMESTAMP WITH TIME ZONE, program_id UUID,
                    work_status VARCHAR(16) NOT NULL, next_step_partner_visible BOOLEAN DEFAULT FALSE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stages (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_order INTEGER NOT NULL, name VARCHAR(200) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_events (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, type VARCHAR(32) NOT NULL, from_stage_id UUID,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stage_completions (
                    interaction_id UUID NOT NULL, stage_id UUID NOT NULL, completed_on DATE NOT NULL,
                    PRIMARY KEY (interaction_id, stage_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS product_agreements (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, product_id UUID NOT NULL, archived_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS attachments (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_id UUID, event_id UUID,
                    original_name VARCHAR(255) NOT NULL, media_type VARCHAR(160) NOT NULL, size_bytes BIGINT NOT NULL,
                    storage_key UUID NOT NULL, checksum CHAR(64), status VARCHAR(32) NOT NULL, kind VARCHAR(32) NOT NULL,
                    revision INTEGER DEFAULT 1 NOT NULL, replaces_id UUID, created_by UUID, version INTEGER DEFAULT 0 NOT NULL,
                    partner_visible BOOLEAN DEFAULT FALSE NOT NULL, deleted_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, concluded_on DATE,
                    valid_until DATE, status VARCHAR(16) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_activities (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, title VARCHAR(300) NOT NULL, planned_start DATE,
                    status VARCHAR(16) NOT NULL
                )
                """
        };
        for (String statement : statements) {
            jdbcTemplate.execute(statement);
        }
    }
}
