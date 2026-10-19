package ru.rtk.crm.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import ru.rtk.crm.access.AccountSyncException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.access.KeycloakAccountConflictException;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.catalog.CatalogArchivedEvent;
import ru.rtk.crm.mail.MailNotifications;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:partner-cabinet;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-partner-cabinet-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.modules.partner.enabled=true"
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

    @MockitoBean
    private AttachmentScanner attachmentScanner;

    // Почта в этом классе подменена: проверяется, какие письма ставит «Запрос вузу». Как письмо попадает в очередь —
    // MailNotificationsTest, работа без модуля почты — PartnerRequestMailOffTest.
    @MockitoBean
    private MailNotifications mailNotifications;

    @Autowired
    private PartnerRequestService partnerRequestService;

    @BeforeEach
    void setUp() throws IOException {
        createSchema();
        for (String table : new String[] {
                "partner_material_receipts", "partner_request_responses", "partner_request_materials", "partner_requests",
                "audit_events", "crm_profile_events", "contact_events", "agreement_activities", "agreement_status_events", "agreements", "attachments",
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
        when(attachmentScanner.scan(any(), anyLong())).thenReturn(AttachmentScanOutcome.CLEAN);
    }

    // P-06, P-07: ответ с файлом после обрыва связи не дублируется, а непроверенный файл нельзя принять.
    @Test
    void kamAsksCoordinatorWhoAnswersOnceWithFileAndKamAcceptsOnlyCheckedFile() throws Exception {
        JsonNode request = createRequest(KAM_A, COORDINATOR, List.of(SHARED_DOCUMENT));
        String requestId = request.path("id").asText();
        assertThat(request.path("status").asText()).isEqualTo("AWAITING_RESPONSE");
        assertThat(request.path("materials").get(0).path("name").asText()).isEqualTo("План сотрудничества.pdf");

        String cabinet = mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests", hasSize(1)))
                .andExpect(jsonPath("$.requests[0].action").value("Прислать список преподавателей курса"))
                .andExpect(jsonPath("$.requests[0].workTitle").value("Внедрение платформы"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(cabinet).doesNotContain("createdByName", "contactName", "внутренний комментарий");
        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_B)))
                .andExpect(jsonPath("$.requests", hasSize(0)));

        MockMultipartFile file = new MockMultipartFile("file", "Список.pdf", "application/pdf",
                "%PDF-1.4 список".getBytes(StandardCharsets.UTF_8));
        String first = respond(PARTNER_A, requestId, 0, "Список во вложении", file, "answer-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNumber").value(containsString("В-")))
                .andExpect(jsonPath("$.fileState").value("CLEAN"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String repeated = respond(PARTNER_A, requestId, 0, "Список во вложении", file, "answer-1")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(repeated).path("receiptNumber")).isEqualTo(objectMapper.readTree(first).path("receiptNumber"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM partner_request_responses", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachments WHERE original_name = 'Список.pdf'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT partner_visible FROM attachments WHERE original_name = 'Список.pdf'",
                Boolean.class)).isFalse();
        respond(PARTNER_A, requestId, 1, "Ещё раз", null, "answer-2")
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/partner-requests/review").with(login(KAM_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(requestId))
                .andExpect(jsonPath("$[0].status").value("RESPONDED"));

        jdbcTemplate.update("UPDATE attachments SET status = 'QUARANTINE' WHERE original_name = 'Список.pdf'");
        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(jsonPath("$.requests[0].status").value("RESPONDED"))
                .andExpect(jsonPath("$.requests[0].responses[0].fileState").value("CHECKING"));
        review(KAM_A, requestId, 1, "ACCEPTED", null, "accept-early")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("проверяется")));
        jdbcTemplate.update("UPDATE attachments SET status = 'CLEAN' WHERE original_name = 'Список.pdf'");
        UUID stageBefore = jdbcTemplate.queryForObject("SELECT current_stage_id FROM interactions WHERE id = ?", UUID.class, WORK_A);
        review(KAM_OTHER, requestId, 1, "ACCEPTED", null, "accept-other").andExpect(status().isNotFound());
        review(MANAGEMENT, requestId, 1, "ACCEPTED", null, "accept-management").andExpect(status().isForbidden());
        review(KAM_A, requestId, 1, "ACCEPTED", null, "accept")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.responses[0].decision").value("ACCEPTED"));
        assertThat(jdbcTemplate.queryForObject("SELECT current_stage_id FROM interactions WHERE id = ?", UUID.class, WORK_A))
                .isEqualTo(stageBefore);
        mockMvc.perform(get("/api/partner-requests/review").with(login(KAM_A))).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(jsonPath("$.requests[0].status").value("ACCEPTED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'PARTNER_RESPONSE_SUBMITTED'",
                Integer.class)).isEqualTo(1);
    }

    // P-08: возврат с причиной, новая версия ответа; вопрос вуза и уточнение школы.
    @Test
    void returnedAnswerShowsReasonAndCoordinatorSendsNewVersionAfterClarification() throws Exception {
        String requestId = createRequest(KAM_A, COORDINATOR, List.of()).path("id").asText();
        respond(PARTNER_A, requestId, 0, "Что именно нужно?", null, "question", "QUESTION")
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/partner-requests/{id}/clarify", requestId)
                        .with(login(LEADER_A)).with(csrf()).header("Idempotency-Key", "clarify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"text\":\"Нужны ФИО и почта преподавателей\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_RESPONSE"));
        respond(PARTNER_A, requestId, 2, "Иванов, Петров", null, "answer").andExpect(status().isCreated());
        review(KAM_A, requestId, 3, "RETURNED", " ", "return-empty").andExpect(status().isBadRequest());
        review(KAM_A, requestId, 3, "RETURNED", "Добавьте почту", "return").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"));
        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(jsonPath("$.requests[0].status").value("RETURNED"))
                .andExpect(jsonPath("$.requests[0].returnReason").value("Добавьте почту"))
                .andExpect(jsonPath("$.requests[0].clarification").value("Нужны ФИО и почта преподавателей"))
                .andExpect(jsonPath("$.requests[0].responses", hasSize(2)));
        respond(PARTNER_A, requestId, 4, "Иванов ivanov@example.test", null, "answer-2").andExpect(status().isCreated());
        review(KAM_A, requestId, 5, "ACCEPTED", null, "accept").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForList("SELECT decision FROM partner_request_responses WHERE kind = 'ANSWER' ORDER BY submitted_at",
                String.class)).containsExactly("RETURNED", "ACCEPTED");
    }

    // Письма «Запроса вузу»: координатору о новом запросе и о возврате, КАМ вуза о каждом ответе; после отката — ничего.
    @Test
    void requestReturnAndReplyQueueLettersOnlyAfterCommit() throws Exception {
        String requestId = createRequest(KAM_A, COORDINATOR, List.of()).path("id").asText();
        verify(mailNotifications).partnerRequest(PARTNER_A, ORGANIZATION_A, "Университет А", UUID.fromString(requestId));
        respond(PARTNER_A, requestId, 0, "Иванов, Петров", null, "mail-answer").andExpect(status().isCreated());
        UUID answerId = jdbcTemplate.queryForObject("SELECT id FROM partner_request_responses WHERE request_id = ?", UUID.class,
                UUID.fromString(requestId));
        verify(mailNotifications).partnerReply(KAM_A, ORGANIZATION_A, WORK_A, "Университет А", answerId);
        review(KAM_A, requestId, 1, "RETURNED", "Добавьте почту", "mail-return").andExpect(status().isOk());
        // Возврат — своё письмо, а не повтор «нового запроса».
        verify(mailNotifications).partnerReturned(PARTNER_A, ORGANIZATION_A, "Университет А", answerId);
        verify(mailNotifications, never()).partnerRequest(PARTNER_A, ORGANIZATION_A, "Университет А", answerId);

        clearInvocations(mailNotifications);
        transactionTemplate.executeWithoutResult(status -> {
            partnerRequestService.create(new CrmProfile(KAM_A, UserRole.USER, TEAM_A, 0), WORK_A,
                    new PartnerRequestModels.PartnerRequestCreate(COORDINATOR, "Откатится", "Ничего", null, List.of()),
                    "mail-rollback", null);
            status.setRollbackOnly();
        });
        verifyNoInteractions(mailNotifications);
    }

    // P-01, P-03: чужой запрос и закрытый доступ; запросы ведёт только КАМ вуза и руководитель его команды.
    @Test
    void requestsAreAddressedToOneCoordinatorAndClosedAccessStopsAnswers() throws Exception {
        mockMvc.perform(post("/api/interactions/{id}/partner-requests", WORK_A)
                        .with(login(KAM_A)).with(csrf()).header("Idempotency-Key", "no-access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(RECTOR, List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.contactId").value(containsString("нет действующего доступа")));
        mockMvc.perform(post("/api/interactions/{id}/partner-requests", WORK_A)
                        .with(login(KAM_A)).with(csrf()).header("Idempotency-Key", "internal-material")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(COORDINATOR, List.of(INTERNAL_DOCUMENT))))
                .andExpect(status().isBadRequest());
        for (UUID stranger : new UUID[] {KAM_OTHER, ADMIN}) {
            mockMvc.perform(post("/api/interactions/{id}/partner-requests", WORK_A)
                            .with(login(stranger)).with(csrf()).header("Idempotency-Key", "stranger-" + stranger)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(COORDINATOR, List.of())))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/interactions/{id}/partner-requests", WORK_A).with(login(LEADER_B)))
                .andExpect(status().isNotFound());
        String requestId = createRequest(LEADER_A, COORDINATOR, List.of()).path("id").asText();
        mockMvc.perform(get("/api/interactions/{id}/partner-requests", WORK_A).with(login(MANAGEMENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/interactions/{id}/partner-requests", WORK_A).with(partnerLogin(PARTNER_A)))
                .andExpect(status().isForbidden());

        respond(PARTNER_B, requestId, 0, "Чужой ответ", null, "foreign").andExpect(status().isNotFound());
        jdbcTemplate.update("UPDATE contacts SET inactive = TRUE WHERE id = ?", COORDINATOR);
        respond(PARTNER_A, requestId, 0, "После закрытия", null, "closed").andExpect(status().isForbidden());
        jdbcTemplate.update("UPDATE contacts SET inactive = FALSE WHERE id = ?", COORDINATOR);
        mockMvc.perform(post("/api/partner-requests/{id}/cancel", requestId)
                        .with(login(KAM_A)).with(csrf()).header("Idempotency-Key", "cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        respond(PARTNER_A, requestId, 1, "После отмены", null, "cancelled").andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM partner_request_responses", Integer.class)).isZero();
    }

    // Н-13 (КАМ): вопрос координатора не запирает готовый ответ.
    @Test
    void coordinatorAnswersWhileOwnQuestionWaitsForClarification() throws Exception {
        String requestId = createRequest(KAM_A, COORDINATOR, List.of()).path("id").asText();
        respond(PARTNER_A, requestId, 0, "Что именно нужно?", null, "q-1", "QUESTION").andExpect(status().isCreated());
        respond(PARTNER_A, requestId, 1, "Пришлю всё сразу: Иванов, Петров", null, "a-1").andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM partner_requests WHERE id = ?", String.class, UUID.fromString(requestId)))
                .isEqualTo("RESPONDED");
    }

    // 4.7 (руководство, 2.5): запрет записи для «Руководства» — 403 раньше проверки тела запроса.
    @Test
    void managementGetsForbiddenBeforeBodyValidation() throws Exception {
        mockMvc.perform(post("/api/organizations/{id}/contacts", ORGANIZATION_A).with(login(MANAGEMENT)).with(csrf())
                        .header("Idempotency-Key", "management-contact").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"email\":\"не почта\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/interactions/{id}/comments", WORK_A).with(login(MANAGEMENT)).with(csrf())
                        .header("Idempotency-Key", "management-comment").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        // w48: переход, создание взаимодействия и назначение ответственного — тоже 403, а не 400 за пустое тело.
        mockMvc.perform(post("/api/interactions/{id}/transitions", WORK_A).with(login(MANAGEMENT)).with(csrf())
                        .header("Idempotency-Key", "management-transition").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/interactions").with(login(MANAGEMENT)).with(csrf())
                        .header("Idempotency-Key", "management-create").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/organizations/{id}/assignment", ORGANIZATION_A).with(login(MANAGEMENT)).with(csrf())
                        .header("Idempotency-Key", "management-assign").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        // КАМ по-прежнему получает 400 за неполное тело: проверка прав его не задевает.
        mockMvc.perform(post("/api/interactions/{id}/transitions", WORK_A).with(login(KAM_A)).with(csrf())
                        .header("Idempotency-Key", "kam-transition-empty").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // Н-15 (2.5): несуществующая дата и значение не того типа — ошибка на поле, а не на «body»; длина — без «от 0».
    @Test
    void unreadableValuesAreReportedOnTheirFields() throws Exception {
        mockMvc.perform(post("/api/interactions").with(login(KAM_A)).with(csrf())
                        .header("Idempotency-Key", "kam-bad-date").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + ORGANIZATION_A + "\",\"title\":\"Т\",\"nextActionAt\":\"2027-02-29T10:00:00+03:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.nextActionAt").value(containsString("такой даты нет")))
                .andExpect(jsonPath("$.fieldErrors.body").doesNotExist());
        mockMvc.perform(post("/api/interactions").with(login(KAM_A)).with(csrf())
                        .header("Idempotency-Key", "kam-bad-uuid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"не-id\",\"title\":\"Т\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.organizationId").value("Значение в неверном формате"));
        mockMvc.perform(post("/api/interactions").with(login(KAM_A)).with(csrf())
                        .header("Idempotency-Key", "kam-long-title").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + ORGANIZATION_A + "\",\"title\":\"" + "я".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value(containsString("не больше 200")));
    }

    // Н-9 и Н-6 (КАМ): закрытие доступа отменяет открытые запросы с причиной; смена почты контакта меняет почту учётки.
    @Test
    void closingAccessCancelsOpenRequestsAndContactEmailFollowsToTheAccount() throws Exception {
        UUID subjectProfile = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_user_profiles WHERE partner_contact_id = ?", UUID.class, COORDINATOR);
        String subject = jdbcTemplate.queryForObject("SELECT subject FROM crm_user_profiles WHERE id = ?", String.class, subjectProfile);
        int version = jdbcTemplate.queryForObject("SELECT version FROM contacts WHERE id = ?", Integer.class, COORDINATOR);
        mockMvc.perform(patch("/api/organizations/{id}/contacts/{contactId}", ORGANIZATION_A, COORDINATOR)
                        .with(login(KAM_A)).with(csrf()).header("Idempotency-Key", "contact-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"name\":\"Координатор\",\"email\":\"new-coordinator@example.test\"}"))
                .andExpect(status().isOk());
        verify(keycloakAccountClient).changeEmail(subject, "new-coordinator@example.test");
        mockMvc.perform(patch("/api/organizations/{id}/contacts/{contactId}", ORGANIZATION_A, COORDINATOR)
                        .with(login(KAM_A)).with(csrf()).header("Idempotency-Key", "contact-email-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + (version + 1) + ",\"name\":\"Координатор\",\"email\":\"иван@почта.рф\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value(containsString("латиницей")));

        String requestId = createRequest(KAM_A, COORDINATOR, List.of()).path("id").asText();
        mockMvc.perform(delete("/api/organizations/{id}/contacts/{contactId}/partner-access", ORGANIZATION_A, COORDINATOR)
                        .with(login(KAM_A)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForMap("SELECT status, cancel_reason FROM partner_requests WHERE id = ?", UUID.fromString(requestId)))
                .containsEntry("STATUS", "CANCELLED")
                .hasEntrySatisfying("CANCEL_REASON", reason -> assertThat((String) reason).contains("Доступ координатора"));
    }

    // P-09: подтверждение относится к версии файла; новая версия приходит неподтверждённой.
    @Test
    void receiptConfirmsOneFileVersionAndNewVersionIsNotConfirmed() throws Exception {
        String requestId = createRequest(KAM_A, COORDINATOR, List.of(SHARED_DOCUMENT)).path("id").asText();
        mockMvc.perform(put("/api/partner/documents/{id}/receipt", SHARED_DOCUMENT).with(partnerLogin(PARTNER_A)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAt").isNotEmpty());
        mockMvc.perform(put("/api/partner/documents/{id}/receipt", SHARED_DOCUMENT).with(partnerLogin(PARTNER_A)).with(csrf()))
                .andExpect(status().isOk());
        for (UUID hidden : new UUID[] {INTERNAL_DOCUMENT, QUARANTINED_DOCUMENT, FOREIGN_DOCUMENT}) {
            mockMvc.perform(put("/api/partner/documents/{id}/receipt", hidden).with(partnerLogin(PARTNER_A)).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        UUID newer = UUID.randomUUID();
        insertDocument(newer, WORK_A, "План сотрудничества.pdf", "CLEAN", true, UUID.randomUUID());
        jdbcTemplate.update("UPDATE attachments SET revision = 2, replaces_id = ? WHERE id = ?", SHARED_DOCUMENT, newer);

        String body = mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode cabinet = objectMapper.readTree(body);
        assertThat(findById(cabinet.path("documents"), SHARED_DOCUMENT).path("receivedAt").isNull()).isFalse();
        assertThat(findById(cabinet.path("documents"), SHARED_DOCUMENT).path("replaced").asBoolean()).isTrue();
        assertThat(findById(cabinet.path("documents"), newer).path("receivedAt").isNull()).isTrue();
        assertThat(findById(cabinet.path("documents"), newer).path("revision").asInt()).isEqualTo(2);
        mockMvc.perform(get("/api/interactions/{id}/partner-requests", WORK_A).with(login(KAM_A)))
                .andExpect(jsonPath("$[0].id").value(requestId))
                .andExpect(jsonPath("$[0].materials[0].receivedAt").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM partner_material_receipts", Integer.class)).isEqualTo(1);
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
                .andExpect(jsonPath("$.agreements", hasSize(2)))
                .andExpect(jsonPath("$.agreements[0].number").value("С-1/2026"))
                .andExpect(jsonPath("$.agreements[0].subject[0]").value("Стажировки студентов"))
                .andExpect(jsonPath("$.agreements[1].number").value("С-2/2026"))
                .andExpect(jsonPath("$.agreements[1].status").value("REVIEW"))
                .andExpect(jsonPath("$.agreements[1].approvalRound").value(2))
                .andExpect(jsonPath("$.agreements[0].approvalRound").doesNotExist())
                .andExpect(jsonPath("$.agreements[1].statusChangedAt").value(containsString("2026-10-03")))
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
    void partnerSeesNameEmailAndPhoneOfOnlyItsOwnKam() throws Exception {
        jdbcTemplate.update("UPDATE organizations SET owner_manager_id = ? WHERE id = ?", LEADER_B, ORGANIZATION_B);
        when(keycloakAccountClient.contact(KAM_A.toString()))
                .thenReturn(Optional.of(new KeycloakAccountClient.UserContact("irina.kam@example.test", "+7 900 000-00-01")));
        when(keycloakAccountClient.contact(LEADER_B.toString()))
                .thenReturn(Optional.of(new KeycloakAccountClient.UserContact("leader.b@example.test", null)));

        String body = mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manager.name").value("Ирина КАМ"))
                .andExpect(jsonPath("$.manager.email").value("irina.kam@example.test"))
                .andExpect(jsonPath("$.manager.phone").value("+7 900 000-00-01"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("leader.b@example.test", "Руководитель Б", "КАМ без вуза");

        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manager.name").value("Руководитель Б"))
                .andExpect(jsonPath("$.manager.email").value("leader.b@example.test"))
                .andExpect(jsonPath("$.manager.phone").doesNotExist());

        when(keycloakAccountClient.contact(KAM_A.toString())).thenThrow(new AccountSyncException("Keycloak недоступен"));
        mockMvc.perform(get("/api/partner/cabinet").with(partnerLogin(PARTNER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manager.name").value("Ирина КАМ"))
                .andExpect(jsonPath("$.manager.email").doesNotExist());
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
        verify(keycloakAccountClient).setCrmRole("kc-rector", UserRole.PARTNER);
        mockMvc.perform(get("/api/partner/cabinet").with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("kc-rector")
                        .claim("realm_access", Map.of("roles", List.of("crm-partner"))))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/partner/cabinet").with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("kc-rector")
                        .claim("realm_access", Map.of("roles", List.of("crm-admin"))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CRM_PROFILE_REQUIRED"));
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
        // Три записи журнала создаются в одну миллисекунду, порядок по времени у них не определён; порядок действий
        // проверяют версии профиля выше.
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class))
                .containsExactlyInAnyOrder("ACCOUNT_CREATED", "ACCOUNT_DISABLED", "ACCOUNT_ENABLED");
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

    private JsonNode createRequest(UUID actor, UUID contact, List<UUID> materials) throws Exception {
        String body = mockMvc.perform(post("/api/interactions/{id}/partner-requests", WORK_A)
                        .with(login(actor)).with(csrf()).header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(contact, materials)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private String requestBody(UUID contact, List<UUID> materials) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "contactId", contact,
                "action", "Прислать список преподавателей курса",
                "expectedResult", "Таблица: ФИО, почта",
                "materialIds", materials
        ));
    }

    private ResultActions respond(UUID partner, String requestId, int version, String message, MockMultipartFile file, String key)
            throws Exception {
        return respond(partner, requestId, version, message, file, key, "ANSWER");
    }

    private ResultActions respond(UUID partner, String requestId, int version, String message, MockMultipartFile file, String key,
                                  String kind) throws Exception {
        var builder = multipart("/api/partner/requests/{id}/responses", requestId);
        if (file != null) {
            builder.file(file);
        }
        return mockMvc.perform(builder
                .param("kind", kind)
                .param("version", Integer.toString(version))
                .param("message", message)
                .header("Idempotency-Key", key)
                .with(partnerLogin(partner)).with(csrf()));
    }

    private ResultActions review(UUID actor, String requestId, int version, String decision, String reason, String key) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("version", version, "decision", decision));
        if (reason != null) {
            body.put("reason", reason);
        }
        return mockMvc.perform(post("/api/partner-requests/{id}/review", requestId)
                .with(login(actor)).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
        UUID agreementInReview = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO agreements (id, organization_id, number, concluded_on, valid_until, status, status_changed_at)
                VALUES (?, ?, 'С-1/2026', DATE '2026-01-15', DATE '2028-12-31', 'ACTIVE', TIMESTAMP WITH TIME ZONE '2026-01-15 11:00:00+03:00'),
                       (?, ?, 'С-2/2026', NULL, NULL, 'REVIEW', TIMESTAMP WITH TIME ZONE '2026-10-03 11:00:00+03:00'),
                       (?, ?, 'С-3/2026', NULL, NULL, 'DRAFT', TIMESTAMP WITH TIME ZONE '2026-10-01 11:00:00+03:00'),
                       (?, ?, 'С-Б', NULL, NULL, 'SIGNING', TIMESTAMP WITH TIME ZONE '2026-10-01 11:00:00+03:00')
                """, agreementA, ORGANIZATION_A, agreementInReview, ORGANIZATION_A, UUID.randomUUID(), ORGANIZATION_A,
                UUID.randomUUID(), ORGANIZATION_B);
        jdbcTemplate.update("""
                INSERT INTO agreement_status_events (id, agreement_id, from_status, to_status, occurred_at)
                VALUES (?, ?, 'DRAFT', 'REVIEW', TIMESTAMP WITH TIME ZONE '2026-09-01 11:00:00+03:00'),
                       (?, ?, 'REVIEW', 'CORRECTION', TIMESTAMP WITH TIME ZONE '2026-09-20 11:00:00+03:00'),
                       (?, ?, 'CORRECTION', 'REVIEW', TIMESTAMP WITH TIME ZONE '2026-10-03 11:00:00+03:00')
                """, UUID.randomUUID(), agreementInReview, UUID.randomUUID(), agreementInReview, UUID.randomUUID(), agreementInReview);
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
        ru.rtk.crm.interaction.StagePropertiesTestSchema.create(jdbcTemplate);
        String[] statements = {
                """
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
                    archived BOOLEAN DEFAULT FALSE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, 
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
                CREATE TABLE IF NOT EXISTS interaction_issues (last_comment VARCHAR(1000), last_comment_by UUID, last_comment_at TIMESTAMP WITH TIME ZONE,
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL,
                    description VARCHAR(1000) NOT NULL, risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE,
                    status VARCHAR(16) DEFAULT 'OPEN' NOT NULL, resolution VARCHAR(1000), created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interactions (interaction_kind_id UUID, workflow_template_id UUID, workflow_version INTEGER, 
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, current_stage_id UUID NOT NULL,
                    next_action VARCHAR(500), next_action_at TIMESTAMP WITH TIME ZONE, program_id UUID,
                    work_status VARCHAR(16) NOT NULL, next_step_partner_visible BOOLEAN DEFAULT FALSE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_stages (requires_agreement BOOLEAN DEFAULT FALSE NOT NULL, lms_role VARCHAR(32), template_stage_id UUID, 
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_order INTEGER NOT NULL, name VARCHAR(200) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS interaction_events (contact_channel VARCHAR(16),
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
                    partner_visible BOOLEAN DEFAULT FALSE NOT NULL, deleted_at TIMESTAMP WITH TIME ZONE, deleted_by UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS partner_requests (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, organization_id UUID NOT NULL, contact_id UUID NOT NULL,
                    action VARCHAR(300) NOT NULL, expected_result VARCHAR(1000) NOT NULL, due_on DATE,
                    status VARCHAR(24) DEFAULT 'AWAITING_RESPONSE' NOT NULL, return_reason VARCHAR(1000), clarification VARCHAR(2000),
                    cancel_reason VARCHAR(1000), version INTEGER DEFAULT 0 NOT NULL, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT partner_requests_return_reason_check CHECK (status <> 'RETURNED' OR return_reason IS NOT NULL)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS partner_request_materials (
                    request_id UUID NOT NULL, attachment_id UUID NOT NULL, PRIMARY KEY (request_id, attachment_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS partner_request_responses (
                    id UUID PRIMARY KEY, request_id UUID NOT NULL, receipt_number VARCHAR(16) NOT NULL UNIQUE, kind VARCHAR(16) NOT NULL,
                    message VARCHAR(4000), attachment_id UUID, submitted_by UUID NOT NULL, submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    decision VARCHAR(16), decision_reason VARCHAR(1000), decided_by UUID, decided_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS partner_material_receipts (
                    attachment_id UUID NOT NULL, profile_id UUID NOT NULL, confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (attachment_id, profile_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreements (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, concluded_on DATE,
                    valid_until DATE, status VARCHAR(16) NOT NULL, status_changed_at TIMESTAMP WITH TIME ZONE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS agreement_status_events (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
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
