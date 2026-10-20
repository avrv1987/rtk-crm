package ru.rtk.crm.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.rtk.crm.access.KeycloakAccountClient;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.privacy.PersonalDataService;
import ru.rtk.crm.privacy.SubjectExportFormat;
import ru.rtk.crm.work.WorkService;

// Модуль почты при включённом флаге: права администратора, пароль SMTP, путь письма через заглушку и через SMTP
// (сервер GreenMail в памяти), повторы и отмена, перепроверка получателя и приглашение по ссылке.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:mail-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-mail-http-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=https://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.sources.settings-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.modules.mail.enabled=true",
        "app.mail.jobs-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
@ExtendWith(OutputCaptureExtension.class)
class MailHttpTest {
    static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    static final UUID TEAM = UUID.fromString("81000000-0000-0000-0000-000000000100");
    static final UUID ADMIN = UUID.fromString("81000000-0000-0000-0000-000000000001");
    static final UUID KAM = UUID.fromString("81000000-0000-0000-0000-000000000002");
    static final UUID LEADER = UUID.fromString("81000000-0000-0000-0000-000000000003");
    static final UUID MANAGEMENT = UUID.fromString("81000000-0000-0000-0000-000000000004");
    static final UUID PARTNER = UUID.fromString("81000000-0000-0000-0000-000000000005");
    static final UUID OPERATOR = UUID.fromString("81000000-0000-0000-0000-000000000006");
    static final UUID ORGANIZATION = UUID.fromString("81000000-0000-0000-0000-000000000201");
    private static final String SECRET = "S3cret-Smtp-Passw0rd";
    private static final Pattern INVITE = Pattern.compile("#/invite/([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MailOutbox outbox;

    @Autowired
    private MailTemplates templates;

    @Autowired
    private MailRepository repository;

    @Autowired
    private ru.rtk.crm.audit.AuditJournalRepository auditJournal;

    @Autowired
    private MailInvitationService invitations;

    @MockitoBean
    private KeycloakAccountClient keycloakAccountClient;

    @MockitoBean
    private OrganizationRepository organizationRepository;

    @MockitoBean
    private WorkService workService;

    @MockitoBean
    private PersonalDataService personalDataService;

    @BeforeEach
    void setUp() {
        MailTestSchema.reset(jdbcTemplate);
        MailTestSchema.profile(jdbcTemplate, ADMIN, "Администратор", "ADMIN", null);
        MailTestSchema.profile(jdbcTemplate, KAM, "Анна КАМ", "USER", TEAM);
        MailTestSchema.profile(jdbcTemplate, LEADER, "Лев Руководитель", "LEADER", TEAM);
        MailTestSchema.profile(jdbcTemplate, MANAGEMENT, "Руководство", "MANAGEMENT", null);
        MailTestSchema.profile(jdbcTemplate, PARTNER, "Координатор", "PARTNER", null);
        MailTestSchema.profile(jdbcTemplate, OPERATOR, "Оператор", "USER", TEAM);
        jdbcTemplate.update("UPDATE crm_user_profiles SET enrolment_operator = TRUE WHERE id = ?", OPERATOR);
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, 'Команда')", TEAM);
        when(keycloakAccountClient.contact(anyString())).thenAnswer(invocation -> Optional.of(
                new KeycloakAccountClient.UserContact(MailTestSchema.email(invocation.getArgument(0)), null)));
        when(keycloakAccountClient.configured()).thenReturn(true);
        when(organizationRepository.findVisibleById(any(), any())).thenReturn(Optional.of(organization()));
    }

    @Test
    void mailScreenIsForAdministratorOnly() throws Exception {
        for (UUID profile : List.of(KAM, LEADER, MANAGEMENT, PARTNER)) {
            mockMvc.perform(get("/api/mail/admin/settings").with(login(profile))).andExpect(status().isForbidden());
            mockMvc.perform(put("/api/mail/admin/settings").with(login(profile)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(1025, SECRET))).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/mail/admin/check").with(login(profile)).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/mail/admin/test").with(login(profile)).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/mail/admin/outbox").with(login(profile))).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/mail/admin/outbox/requeue").with(login(profile)).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/mail/admin/subject-report").with(login(profile)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"s@example.test\",\"query\":{\"name\":\"Иванов\"}}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/mail/admin/settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mail/admin/settings").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("STUB"))
                .andExpect(jsonPath("$.passwordState").value("NONE"))
                .andExpect(jsonPath("$.queue.queued").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class)).isZero();
    }

    @Test
    void smtpPasswordIsEncryptedAndNeverReturnedOrLogged(CapturedOutput output) throws Exception {
        String saved = mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(1025, SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SMTP"))
                .andExpect(jsonPath("$.passwordState").value("SET"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String stored = jdbcTemplate.queryForObject("SELECT password_encrypted FROM mail_settings", String.class);
        assertThat(stored).isNotBlank().doesNotContain(SECRET);
        String view = mockMvc.perform(get("/api/mail/admin/settings").with(login(ADMIN)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(1025, null)))
                .andExpect(jsonPath("$.passwordState").value("SET"));
        assertThat(jdbcTemplate.queryForObject("SELECT password_encrypted FROM mail_settings", String.class)).isEqualTo(stored);
        mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(1025, null).replace("}", ",\"clearPassword\":true}")))
                .andExpect(jsonPath("$.passwordState").value("NONE"));
        mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SMTP\",\"fromAddress\":\"crm@example.test\",\"fromName\":\"CRM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.host").exists());

        List<String> audit = jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE action = 'MAIL_SETTINGS_CHANGED'", String.class);
        assertThat(audit).hasSize(3).anyMatch(details -> details.contains("пароль заменён")).anyMatch(details -> details.contains("пароль удалён"))
                .noneMatch(details -> details.contains(SECRET) || details.contains("smtp-user"));
        assertThat(saved + view).doesNotContain(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void stubTransportBuildsTheWholeLetterAndKeepsItInTheJournal() throws Exception {
        mockMvc.perform(post("/api/mail/admin/check").with(login(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        String sent = mockMvc.perform(post("/api/mail/admin/test").with(login(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.transport").value("STUB"))
                .andExpect(jsonPath("$.recipient").value("a***@example.test"))
                .andExpect(jsonPath("$.kind").value("TEST"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = objectMapper.readTree(sent).path("id").asText();
        mockMvc.perform(get("/api/mail/admin/outbox").with(login(ADMIN)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].kindTitle").value("Тестовое письмо"));
        mockMvc.perform(get("/api/mail/admin/outbox/{id}", id).with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Тестовое письмо CRM ИТ Школы РТК"))
                .andExpect(jsonPath("$.from").value("CRM ИТ Школы РТК <crm@localhost>"))
                .andExpect(jsonPath("$.text").value(containsString("настройки почты в CRM работают")))
                .andExpect(jsonPath("$.html").value(containsString("<p")))
                .andExpect(jsonPath("$.messageId").exists())
                .andExpect(jsonPath("$.recipient").value("a***@example.test"));
        String mime = jdbcTemplate.queryForObject("SELECT mime_message FROM mail_outbox", String.class);
        assertThat(mime).contains("multipart/alternative", "Auto-Submitted: auto-generated", "To: a");
        assertThat(jdbcTemplate.queryForObject("SELECT recipient_email FROM mail_outbox", String.class)).isNull();
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class))
                .contains("MAIL_CONNECTION_CHECKED", "MAIL_TEST_SENT", "MAIL_LETTER_VIEWED");
    }

    @Test
    void smtpTransportDeliversToAnInMemoryServer() throws Exception {
        GreenMail server = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        server.start();
        try {
            server.setUser("crm@example.test", "smtp-user", SECRET);
            int port = server.getSmtp().getPort();
            mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(port, SECRET))).andExpect(status().isOk());
            mockMvc.perform(post("/api/mail/admin/check").with(login(ADMIN)).with(csrf()))
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.message").value(containsString("установлено")));
            mockMvc.perform(post("/api/mail/admin/test").with(login(ADMIN)).with(csrf()))
                    .andExpect(jsonPath("$.status").value("SENT"))
                    .andExpect(jsonPath("$.transport").value("SMTP"));
            MimeMessage[] received = server.getReceivedMessages();
            assertThat(received).hasSize(1);
            assertThat(received[0].getSubject()).isEqualTo("Тестовое письмо CRM ИТ Школы РТК");
            assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo(MailTestSchema.email("kc-" + ADMIN));
            assertThat(GreenMailUtil.getBody(received[0])).contains("text/html", "text/plain");

            mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(port, "wrong-password"))).andExpect(status().isOk());
            mockMvc.perform(post("/api/mail/admin/check").with(login(ADMIN)).with(csrf()))
                    .andExpect(jsonPath("$.ok").value(false));
        } finally {
            server.stop();
        }
    }

    @Test
    void failedLettersAreRetriedThenStopAndCanBeRetriedOrCancelledByAdministrator() throws Exception {
        mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(MailTestSchema.closedPort(), null))).andExpect(status().isOk());
        outbox.toProfile("assign:1", MailKind.ASSIGNMENT, KAM, ORGANIZATION, templates.assignment("Колледж связи", ORGANIZATION));
        for (int attempt = 1; attempt <= MailRepository.MAX_ATTEMPTS; attempt++) {
            jdbcTemplate.update("UPDATE mail_outbox SET next_attempt_at = ?", OffsetDateTime.now().minusSeconds(1));
            assertThat(outbox.sendBatch()).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM mail_outbox", Integer.class)).isEqualTo(attempt);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM mail_outbox", String.class)).isEqualTo("ERROR");
        assertThat(jdbcTemplate.queryForObject("SELECT last_error FROM mail_outbox", String.class)).isNotBlank();
        String id = jdbcTemplate.queryForObject("SELECT CAST(id AS VARCHAR) FROM mail_outbox", String.class);

        mockMvc.perform(post("/api/mail/admin/outbox/{id}/retry", id).with(login(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0));
        mockMvc.perform(post("/api/mail/admin/outbox/{id}/cancel", id).with(login(ADMIN)).with(csrf()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/mail/admin/outbox/{id}/retry", id).with(login(ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAIL_LETTER_STATE"));
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class))
                .contains("MAIL_OUTBOX_REQUEUED", "MAIL_OUTBOX_CANCELLED");
    }

    @Test
    void recipientIsCheckedAgainBeforeSending() {
        outbox.toProfile("a", MailKind.ASSIGNMENT, KAM, ORGANIZATION, templates.assignment("Колледж связи", ORGANIZATION));
        outbox.toProfile("b", MailKind.SITE_REQUEST, KAM, ORGANIZATION, templates.siteRequest("Колледж связи", ORGANIZATION));
        outbox.toProfile("c", MailKind.DIGEST, OPERATOR, null, templates.test());
        outbox.toProfile("d", MailKind.ASSIGNMENT, LEADER, ORGANIZATION, templates.assignment("Колледж связи", ORGANIZATION));
        outbox.toProfile("e", MailKind.AGREEMENT_EXPIRY, MANAGEMENT, ORGANIZATION, templates.test());
        outbox.toProfile("f", MailKind.ASSIGNMENT, KAM, UUID.randomUUID(), templates.test());
        outbox.toProfile("g", MailKind.PARTNER_REQUEST, PARTNER, ORGANIZATION, templates.partnerRequest("Колледж связи"));
        outbox.toProfile("p", MailKind.PARTNER_ACCEPTED, PARTNER, ORGANIZATION, templates.partnerAccepted("Колледж связи"));
        jdbcTemplate.update("INSERT INTO mail_preferences (profile_id, topic) VALUES (?, 'PARTNER_ACCEPTED')", PARTNER);
        jdbcTemplate.update("UPDATE crm_user_profiles SET partner_organization_id = ? WHERE id = ?", UUID.randomUUID(), PARTNER);
        jdbcTemplate.update("INSERT INTO mail_preferences (profile_id, topic) VALUES (?, 'ASSIGNMENT')", LEADER);
        when(organizationRepository.findVisibleById(any(), eq(ORGANIZATION))).thenReturn(Optional.of(organization()));
        when(organizationRepository.findVisibleById(any(), any(UUID.class))).thenAnswer(invocation ->
                ORGANIZATION.equals(invocation.getArgument(1)) ? Optional.of(organization()) : Optional.empty());

        assertThat(outbox.sendBatch()).isEqualTo(1);

        assertThat(reason("a")).isNull();
        assertThat(reason("b")).contains("роль");
        assertThat(reason("c")).contains("роль");
        assertThat(reason("d")).contains("отключил");
        assertThat(reason("e")).contains("роль");
        assertThat(reason("f")).contains("не виден");
        assertThat(reason("g")).contains("не виден");
        assertThat(reason("p")).contains("отключил");

        outbox.toProfile("h", MailKind.ASSIGNMENT, KAM, ORGANIZATION, templates.assignment("Колледж связи", ORGANIZATION));
        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", KAM);
        outbox.sendBatch();
        assertThat(reason("h")).contains("заблокирован");
    }

    // Часы Windows идут шагами около миллисекунды, а база округляет время до микросекунд вверх. Время, записанное
    // для сравнения, не должно после округления оказаться позже того же момента часов: иначе проход отправщика сразу
    // после «Повторить» не видел письма, закрытая ссылка ещё открывалась, а два события журнала менялись местами.
    @Test
    void timesWrittenForComparisonAreNotRoundedIntoTheFuture() {
        OffsetDateTime tick = OffsetDateTime.now().withNano(123_456_700);
        outbox.toProfile("retry:tick", MailKind.ASSIGNMENT, KAM, ORGANIZATION, templates.test());
        UUID letter = repository.findByKey("retry:tick").orElseThrow().id();
        jdbcTemplate.update("UPDATE mail_outbox SET status = 'ERROR' WHERE id = ?", letter);
        assertThat(repository.retry(letter, tick)).isTrue();
        assertThat(repository.findDue(tick, 10)).extracting(MailRepository.OutboxRow::id).containsExactly(letter);

        UUID invitation = UUID.randomUUID();
        repository.insertInvitation(invitation, "hash-tick", KAM, tick.minusMinutes(1), tick.plusDays(1));
        repository.closeOpenInvitations(KAM, tick);
        assertThat(repository.invitationOpen(invitation, tick)).isFalse();

        for (int index = 0; index < 20; index++) {
            auditJournal.record(ru.rtk.crm.audit.AuditAction.MAIL_INVITATION_SENT, ADMIN, "PROFILE", KAM, null, "событие " + index, "rq-" + index);
        }
        assertThat(auditJournal.findByObject("PROFILE", KAM, 50)).extracting(ru.rtk.crm.audit.AuditEntry::details)
                .containsExactlyElementsOf(java.util.stream.IntStream.iterate(19, index -> index - 1).limit(20)
                        .mapToObj(index -> "событие " + index).toList());
    }

    @Test
    void sameEventIsQueuedOnce() {
        assertThat(outbox.toProfile("assign:x", MailKind.ASSIGNMENT, KAM, ORGANIZATION, templates.test())).isTrue();
        assertThat(outbox.toProfile("assign:x", MailKind.ASSIGNMENT, KAM, ORGANIZATION, templates.test())).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mail_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void newEmployeeGetsLinkInsteadOfPasswordOnScreenAndTheLinkWorksOnce(CapturedOutput output) throws Exception {
        GreenMail server = smtpServer();
        try {
            when(keycloakAccountClient.createEmployeeUser(eq("petrova"), eq("petrova@example.test"), anyString(), anyString(), anyString()))
                    .thenReturn("kc-petrova");
            String body = mockMvc.perform(post("/api/admin/crm-profiles").with(login(ADMIN)).with(csrf())
                            .header("Idempotency-Key", "create-1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\":\"Петрова Анна\",\"login\":\"petrova\",\"email\":\"petrova@example.test\",\"role\":\"USER\",\"teamId\":\""
                                    + TEAM + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
                    .andExpect(jsonPath("$.invitationSentTo").value("p***@example.test"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("petrova@example.test");
            assertThat(jdbcTemplate.queryForObject("SELECT details FROM audit_events WHERE action = 'ACCOUNT_CREATED'", String.class))
                    .contains("приглашение отправлено");

            // Н-1: до отправки в очереди только заглушка ссылки — действующего токена нет ни в базе, ни у администратора.
            String queued = jdbcTemplate.queryForObject("SELECT body_text || body_html FROM mail_outbox WHERE kind = 'INVITATION'", String.class);
            assertThat(queued).contains("Логин: petrova", "https://crm.test/#/invite/***").doesNotContain("Пароль:");
            assertThat(INVITE.matcher(queued).find()).isFalse();
            assertThat(outbox.sendBatch()).isEqualTo(1);
            String token = token(received(server, 0));
            assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM mail_invitations", String.class))
                    .isEqualTo(MailCodes.sha256(token));
            assertNoTokenVisibleToAdministrator(token);

            String redeemed = mockMvc.perform(redeem(token))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.login").value("petrova"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String password = objectMapper.readTree(redeemed).path("temporaryPassword").asText();
            assertThat(password).hasSize(16);
            verify(keycloakAccountClient).resetTemporaryPassword("kc-petrova", password);
            mockMvc.perform(redeem(token))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("MAIL_INVITATION_USED"));
            mockMvc.perform(redeem("unknown-token"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MAIL_INVITATION_INVALID"));
            assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class))
                    .contains("MAIL_INVITATION_SENT", "MAIL_INVITATION_REDEEMED");
            assertThat(output.getAll()).doesNotContain(password).doesNotContain(token);
        } finally {
            server.stop();
        }
    }

    @Test
    void passwordResetSendsNewLinkAndOldLinkStopsWorking() throws Exception {
        GreenMail server = smtpServer();
        try {
            mockMvc.perform(post("/api/admin/crm-profiles/{id}/account-password-reset", KAM).with(login(ADMIN)).with(csrf())
                            .header("Idempotency-Key", "reset-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
                    .andExpect(jsonPath("$.invitationSentTo").value("a***@example.test"));
            outbox.sendBatch();
            String first = token(received(server, 0));
            mockMvc.perform(post("/api/admin/crm-profiles/{id}/account-password-reset", KAM).with(login(ADMIN)).with(csrf())
                    .header("Idempotency-Key", "reset-2")).andExpect(status().isOk());
            mockMvc.perform(redeem(first)).andExpect(status().isGone());
            outbox.sendBatch();
            assertThat(jdbcTemplate.queryForList("SELECT status FROM mail_outbox WHERE kind = 'PASSWORD_RESET' ORDER BY created_at", String.class))
                    .containsExactlyInAnyOrder("SENT", "SENT");
            // Н-10 (w44): письмо сброса пароля — свой вид в журнале почты, а не «Приглашение»; токен в нём скрыт так же.

            // Повторная отправка письма администратором: адресат получает новую ссылку, прежняя перестаёт работать,
            // а у администратора в журнале по-прежнему только заглушка.
            String second = token(received(server, 1));
            String id = jdbcTemplate.queryForObject(
                    "SELECT CAST(o.id AS VARCHAR) FROM mail_outbox o JOIN mail_invitations i ON o.event_key = 'invitation:' || CAST(i.id AS VARCHAR)"
                            + " WHERE i.redeemed_at IS NULL AND i.expires_at > CURRENT_TIMESTAMP", String.class);
            jdbcTemplate.update("UPDATE mail_outbox SET status = 'ERROR' WHERE id = CAST(? AS UUID)", id);
            mockMvc.perform(post("/api/mail/admin/outbox/{id}/retry", id).with(login(ADMIN)).with(csrf())).andExpect(status().isOk());
            outbox.sendBatch();
            String third = token(received(server, 2));
            assertThat(third).isNotEqualTo(second);
            mockMvc.perform(redeem(second)).andExpect(status().isNotFound());
            assertNoTokenVisibleToAdministrator(third);
            mockMvc.perform(redeem(third)).andExpect(status().isOk());
        } finally {
            server.stop();
        }
    }

    @Test
    void stubModeGivesPasswordOnScreenAndAdministratorNeverGetsAnInvitationLink() throws Exception {
        mockMvc.perform(post("/api/admin/crm-profiles/{id}/account-password-reset", KAM).with(login(ADMIN)).with(csrf())
                        .header("Idempotency-Key", "reset-stub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").exists())
                .andExpect(jsonPath("$.invitationSentTo").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE kind IN ('INVITATION', 'PASSWORD_RESET')", Integer.class)).isZero();
        // Н-5: адрес отправителя по умолчанию не мешает сохранить другие настройки, а новый неверный адрес отклоняется.
        mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"STUB\",\"fromAddress\":\"CRM@localhost\",\"fromName\":\"CRM\"}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"STUB\",\"fromAddress\":\"other@localhost\",\"fromName\":\"CRM\"}"))
                .andExpect(status().isBadRequest());
        // Письма до 2.5 хранили токен в тексте: при показе он скрывается.
        assertThat(MailOutbox.hideInvitationToken("Получить пароль: https://crm.test/#/invite/AbCdEf_-0123456789xyz"))
                .isEqualTo("Получить пароль: https://crm.test/#/invite/***");
        // Ссылку на новый пароль администратора не выдаёт и сам сервис приглашений.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> invitations.invite(ADMIN, ADMIN, "admin", "a@example.test", false, "rq"))
                .isInstanceOf(ru.rtk.crm.interaction.InteractionValidationException.class);
    }

    private void assertNoTokenVisibleToAdministrator(String token) throws Exception {
        String journal = mockMvc.perform(get("/api/mail/admin/outbox").with(login(ADMIN)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(journal).doesNotContain(token);
        for (String id : jdbcTemplate.queryForList("SELECT CAST(id AS VARCHAR) FROM mail_outbox WHERE kind IN ('INVITATION', 'PASSWORD_RESET')", String.class)) {
            String view = mockMvc.perform(get("/api/mail/admin/outbox/{id}", id).with(login(ADMIN)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(view).doesNotContain(token).contains("#/invite/***");
        }
        String stored = String.join("", jdbcTemplate.queryForList(
                "SELECT COALESCE(mime_message, '') || body_text || COALESCE(body_html, '') FROM mail_outbox", String.class));
        assertThat(stored.replaceAll("=\\r?\\n", "")).doesNotContain(token);
    }

    private GreenMail smtpServer() throws Exception {
        GreenMail server = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        server.start();
        server.setUser("crm@example.test", "smtp-user", "invite-smtp-pass");
        mockMvc.perform(put("/api/mail/admin/settings").with(login(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(smtpSettings(server.getSmtp().getPort(), "invite-smtp-pass"))).andExpect(status().isOk());
        return server;
    }

    private static String received(GreenMail server, int index) throws Exception {
        // Ожидание — страховка на случай доставки в потоке GreenMail. Настоящая причина прежних падений — время
        // повтора, округлённое базой вверх (см. timesWrittenForComparisonAreNotRoundedIntoTheFuture).
        server.waitForIncomingEmail(5000, index + 1);
        MimeMessage[] messages = server.getReceivedMessages();
        assertThat(messages.length).isGreaterThan(index);
        return plainText(messages[index]);
    }

    private static String plainText(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            jakarta.mail.Multipart multipart = (jakarta.mail.Multipart) part.getContent();
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < multipart.getCount(); index++) {
                text.append(plainText(multipart.getBodyPart(index)));
            }
            return text.toString();
        }
        return "";
    }

    @Test
    void subjectReportGoesToTheGivenAddressAndTheAttachmentIsNotKept() throws Exception {
        byte[] pdf = "%PDF-1.4 сведения".getBytes(StandardCharsets.UTF_8);
        when(personalDataService.export(any(), any(), eq(SubjectExportFormat.PDF), anyString()))
                .thenReturn(new PersonalDataService.SubjectExport(pdf, SubjectExportFormat.PDF));
        String row = mockMvc.perform(post("/api/mail/admin/subject-report").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"Subject@Example.test\",\"query\":{\"email\":\"subject@example.test\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.recipient").value("s***@example.test"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(jdbcTemplate.queryForObject("SELECT attachment_content FROM mail_outbox", byte[].class)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT mime_message FROM mail_outbox", String.class)).doesNotContain("application/pdf");
        JsonNode view = objectMapper.readTree(mockMvc.perform(get("/api/mail/admin/outbox/{id}", objectMapper.readTree(row).path("id").asText())
                .with(login(ADMIN))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(view.path("attachments")).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT details FROM audit_events WHERE action = 'MAIL_SUBJECT_REPORT_SENT'", String.class))
                .doesNotContain("subject@");
        mockMvc.perform(post("/api/mail/admin/subject-report").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"не почта\",\"query\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void employeeChoosesWhatToReceiveByRole() throws Exception {
        mockMvc.perform(get("/api/mail/preferences").with(login(KAM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(MailTestSchema.email("kc-" + KAM)))
                .andExpect(jsonPath("$.topics[?(@.topic == 'DIGEST')].enabled").value(true))
                .andExpect(jsonPath("$.topics[?(@.topic == 'SITE_REQUEST')]").isEmpty())
                .andExpect(jsonPath("$.topics[?(@.topic == 'ADMIN_ALERTS')]").isEmpty());
        mockMvc.perform(put("/api/mail/preferences").with(login(KAM)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disabledTopics\":[\"DIGEST\",\"ADMIN_ALERTS\"]}"))
                .andExpect(jsonPath("$.topics[?(@.topic == 'DIGEST')].enabled").value(false));
        assertThat(jdbcTemplate.queryForList("SELECT topic FROM mail_preferences WHERE profile_id = ?", String.class, KAM))
                .containsExactly("DIGEST");
        mockMvc.perform(get("/api/mail/preferences").with(login(ADMIN)))
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].topic").value("ADMIN_ALERTS"));
        mockMvc.perform(get("/api/mail/preferences").with(login(OPERATOR)))
                .andExpect(jsonPath("$.topics").isEmpty());
        mockMvc.perform(get("/api/mail/preferences").with(login(MANAGEMENT)))
                .andExpect(jsonPath("$.topics[0].topic").value("WEEKLY"));
        // Координатору вуза — одна отключаемая тема: школа приняла его ответ (решение владельца 21.10).
        mockMvc.perform(get("/api/mail/preferences").with(login(PARTNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].topic").value("PARTNER_ACCEPTED"));
        mockMvc.perform(put("/api/mail/preferences").with(login(PARTNER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disabledTopics\":[\"PARTNER_ACCEPTED\",\"DIGEST\"]}"))
                .andExpect(jsonPath("$.topics[0].enabled").value(false));
        assertThat(jdbcTemplate.queryForList("SELECT topic FROM mail_preferences WHERE profile_id = ?", String.class, PARTNER))
                .containsExactly("PARTNER_ACCEPTED");
    }

    private static MockHttpServletRequestBuilder redeem(String token) {
        return post("/api/mail/invitations").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}");
    }

    private String reason(String key) {
        return jdbcTemplate.queryForObject("SELECT last_error FROM mail_outbox WHERE event_key = ?", String.class, key);
    }

    private static String token(String text) {
        Matcher matcher = INVITE.matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String smtpSettings(int port, String password) {
        return "{\"mode\":\"SMTP\",\"host\":\"127.0.0.1\",\"port\":" + port + ",\"security\":\"NONE\",\"username\":\"smtp-user\","
                + (password == null ? "" : "\"password\":\"" + password + "\",")
                + "\"fromAddress\":\"crm@example.test\",\"fromName\":\"CRM ИТ Школы РТК\"}";
    }

    private static Organization organization() {
        return org.mockito.Mockito.mock(Organization.class);
    }

    static RequestPostProcessor login(UUID profile) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("kc-" + profile));
    }
}
