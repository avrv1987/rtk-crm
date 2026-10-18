package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.ProfileSessionsEnded;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;

// Основа канала MAX при включённом модуле: свои таблицы, вебхук с секретом и дедупликацией, привязка с
// подтверждением в CRM, события жизненного цикла и исходящая очередь с перепроверкой получателя.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:max-channel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:ru/rtk/crm/max/base-schema.sql'",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-max-channel-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=https://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.sources.settings-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.modules.max.enabled=true",
        "app.max.jobs-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
@ExtendWith(OutputCaptureExtension.class)
class MaxChannelHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID ADMIN = UUID.fromString("61000000-0000-0000-0000-000000000001");
    private static final UUID KAM = UUID.fromString("61000000-0000-0000-0000-000000000002");
    private static final UUID KAM_2 = UUID.fromString("61000000-0000-0000-0000-000000000003");
    private static final UUID OPERATOR = UUID.fromString("61000000-0000-0000-0000-000000000004");
    private static final UUID PARTNER = UUID.fromString("61000000-0000-0000-0000-000000000005");
    private static final UUID MANAGEMENT = UUID.fromString("61000000-0000-0000-0000-000000000006");
    private static final UUID ORGANIZATION = UUID.fromString("61000000-0000-0000-0000-000000000101");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MaxSettingsService settingsService;

    @Autowired
    private MaxSettingsRepository settingsRepository;

    @Autowired
    private MaxInboxProcessor inbox;

    @Autowired
    private MaxOutbox outbox;

    @Autowired
    private MaxLinkRepository linkRepository;

    @Autowired
    private MaxSessionRepository sessionRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MaxClient client;

    @MockitoBean
    private OrganizationRepository organizationRepository;

    private String secret;

    @BeforeEach
    void setUp() {
        for (String table : List.of("max_sessions", "max_outbox", "max_inbox", "max_link_codes", "max_links", "audit_events")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        insertProfile(ADMIN, "Администратор", "ADMIN", false);
        insertProfile(KAM, "Анна КАМ", "USER", false);
        insertProfile(KAM_2, "Борис КАМ", "USER", false);
        insertProfile(OPERATOR, "Оператор", "USER", true);
        insertProfile(PARTNER, "Представитель", "PARTNER", false);
        insertProfile(MANAGEMENT, "Руководство", "MANAGEMENT", false);
        settingsService.update(new CrmProfile(ADMIN, UserRole.ADMIN, null, 0), new MaxSettingsService.UpdateRequest("bot-token", false), "test");
        settingsRepository.saveBotName("rtk_bot");
        secret = settingsService.webhookSecret().orElseThrow();
        when(client.post(anyString(), anyString(), any())).thenReturn(response(200));
    }

    @Test
    void moduleTablesAreCreatedByItsOwnFlywayHistory() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT LOWER(table_name) FROM information_schema.tables WHERE LOWER(table_name) LIKE 'max%' OR LOWER(table_name) LIKE 'flyway%'",
                String.class);
        assertThat(tables).contains("max_settings", "max_links", "max_link_codes", "max_inbox", "max_outbox", "max_sessions",
                MaxModuleConfiguration.SCHEMA_HISTORY_TABLE);
        assertThat(tables).doesNotContain("flyway_schema_history");
    }

    @Test
    void configReportsModuleOn() throws Exception {
        mockMvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.modules.max").value(true));
    }

    @Test
    void webhookRejectsMissingOrWrongSecretAndStoresNothing() throws Exception {
        String event = botStarted(777, "Анна", null, 1000);
        mockMvc.perform(post("/api/max/webhook").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/max/webhook").header(MaxWebhookController.SECRET_HEADER, secret + "x")
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isUnauthorized());
        assertThat(count("max_inbox")).isZero();
    }

    @Test
    void webhookStoresEventOnceAndAcceptsRedeliveryWithoutCsrfOrSession() throws Exception {
        String event = botStarted(777, "Анна", null, 1000);
        webhook(event).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        webhook(event).andExpect(status().isOk());
        assertThat(count("max_inbox")).isEqualTo(1);
        assertThat(settingsRepository.find().lastEventAt()).isNotNull();
    }

    @Test
    void twoPressesOfTheSameKeyboardAreDifferentEvents() throws Exception {
        webhook(callback("kbd-1", 1000)).andExpect(status().isOk());
        webhook(callback("kbd-1", 2000)).andExpect(status().isOk());
        webhook(callback("kbd-1", 2000)).andExpect(status().isOk());
        assertThat(count("max_inbox")).isEqualTo(2);
    }

    @Test
    void oversizedEventIsRejected() throws Exception {
        String big = "{\"update_type\":\"message_created\",\"timestamp\":1,\"pad\":\"" + "x".repeat(MaxWebhookController.BODY_LIMIT) + "\"}";
        webhook(big).andExpect(status().isPayloadTooLarge());
        assertThat(count("max_inbox")).isZero();
    }

    @Test
    void forwardedLinkDoesNotLinkAnotherAccountAndLinkNeedsConfirmationInCrm() throws Exception {
        String code = requestLinkCode(KAM);
        webhook(botStarted(777, "Анна", code, now(1))).andExpect(status().isOk());
        inbox.processBatch();
        webhook(botStarted(888, "Чужой", code, now(2))).andExpect(status().isOk());
        inbox.processBatch();

        assertThat(count("max_links")).isZero();
        mockMvc.perform(get("/api/max/link").with(login(KAM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FOUND"))
                .andExpect(jsonPath("$.candidateName").value("Анна"));
        assertThat(replyTo(888)).contains(MaxLinkService.TEXT_INVALID_LINK);
        assertThat(replyTo(777)).contains(MaxLinkService.TEXT_CONFIRM_IN_CRM);

        mockMvc.perform(post("/api/max/link/confirm").with(login(KAM)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.maxName").value("Анна"));
        assertThat(linkRepository.findByProfile(KAM).orElseThrow().maxUserId()).isEqualTo(777);
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class))
                .contains("MAX_LINK_REQUESTED", "MAX_LINKED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM max_link_codes", Integer.class)).isZero();
    }

    @Test
    void codeIsStoredOnlyAsHashAndExpires() throws Exception {
        String code = requestLinkCode(KAM);
        String stored = jdbcTemplate.queryForObject("SELECT code_hash FROM max_link_codes WHERE profile_id = ?", String.class, KAM);
        assertThat(stored).isEqualTo(MaxCodes.sha256(code)).doesNotContain(code);
        jdbcTemplate.update("UPDATE max_link_codes SET expires_at = ?", OffsetDateTime.now().minusMinutes(1));
        webhook(botStarted(777, "Анна", code, now(1)));
        inbox.processBatch();
        assertThat(replyTo(777)).contains(MaxLinkService.TEXT_INVALID_LINK);
        mockMvc.perform(post("/api/max/link/confirm").with(login(KAM)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAX_LINK_EXPIRED"));
    }

    @Test
    void oneMaxAccountCannotBeLinkedToTwoProfiles() throws Exception {
        linkRepository.replaceLink(KAM_2, 777, "Анна", OffsetDateTime.now());
        String code = requestLinkCode(KAM);
        webhook(botStarted(777, "Анна", code, now(1)));
        inbox.processBatch();
        mockMvc.perform(post("/api/max/link/confirm").with(login(KAM)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAX_ACCOUNT_TAKEN"));
    }

    @Test
    void notMeClearsTheFoundAccount() throws Exception {
        String code = requestLinkCode(KAM);
        webhook(botStarted(888, "Чужой", code, now(1)));
        inbox.processBatch();
        mockMvc.perform(post("/api/max/link/reject").with(login(KAM)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NONE"));
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class)).contains("MAX_LINK_REJECTED");
        mockMvc.perform(post("/api/max/link/confirm").with(login(KAM)).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void onlyOwnWorkRolesCanLinkAndOnlyAdminSeesBotSettings() throws Exception {
        for (UUID profile : List.of(ADMIN, OPERATOR, MANAGEMENT, PARTNER)) {
            mockMvc.perform(post("/api/max/link").with(login(profile)).with(csrf())).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/max/admin/settings").with(login(KAM))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/max/admin/settings").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenState").value("SCREEN"))
                .andExpect(jsonPath("$.botName").value("rtk_bot"))
                .andExpect(jsonPath("$.webhookUrl").value("https://crm.test/api/max/webhook"))
                .andExpect(jsonPath("$.token").doesNotExist());
        mockMvc.perform(post("/api/max/link").with(login(KAM))).andExpect(status().isForbidden());
    }

    @Test
    void newTokenGetsNewWebhookSecret() throws Exception {
        mockMvc.perform(put("/api/max/admin/settings").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"another-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botName").doesNotExist());
        assertThat(settingsService.webhookSecret().orElseThrow()).isNotEqualTo(secret).matches("[A-Za-z0-9]{32}");
        assertThat(jdbcTemplate.queryForObject("SELECT token_encrypted FROM max_settings", String.class)).doesNotContain("another-token");
        webhook(botStarted(777, "Анна", null, 1)).andExpect(status().isUnauthorized());
    }

    @Test
    void environmentTokenIsStoredOnceThenBotNameAndWebhookAreSetUp(CapturedOutput output) throws Exception {
        String environmentToken = "env-bot-token-5f3a9c";
        clearStoredToken();
        when(client.get(environmentToken, "/me")).thenReturn(json("{\"user_id\":1,\"is_bot\":true,\"username\":\"env_bot\"}"));
        when(client.post(eq(environmentToken), eq("/subscriptions"), any())).thenReturn(json("{\"success\":true}"));

        settingsService.bootstrap(environmentToken);

        assertThat(settingsService.token()).contains(environmentToken);
        String webhookSecret = settingsService.webhookSecret().orElseThrow();
        assertThat(webhookSecret).matches("[A-Za-z0-9]{32}");
        MaxSettingsRepository.Stored stored = settingsRepository.find();
        assertThat(stored.tokenEncrypted()).doesNotContain(environmentToken);
        assertThat(stored.botName()).isEqualTo("env_bot");
        assertThat(stored.subscribedAt()).isNotNull();
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq(environmentToken), eq("/subscriptions"), body.capture());
        assertThat(objectMapper.valueToTree(body.getValue()).path("url").asText()).isEqualTo("https://crm.test/api/max/webhook");
        assertThat(objectMapper.valueToTree(body.getValue()).path("secret").asText()).isEqualTo(webhookSecret);
        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE action = 'MAX_SETTINGS_CHANGED'", String.class))
                .containsExactly("токен бота задан из окружения (MAX_BOT_TOKEN)");

        // Повторный старт: токен и секрет те же, журнал и подписка не повторяются.
        settingsService.bootstrap(environmentToken);
        assertThat(settingsService.webhookSecret()).contains(webhookSecret);
        verify(client).post(eq(environmentToken), eq("/subscriptions"), any());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'MAX_SETTINGS_CHANGED'", Integer.class))
                .isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(environmentToken);
    }

    @Test
    void environmentTokenDoesNotOverwriteTokenSavedByAdministrator() {
        settingsService.bootstrap("env-bot-token-other");

        assertThat(settingsService.token()).contains("bot-token");
        assertThat(settingsService.webhookSecret()).contains(secret);
        verify(client, never()).get(anyString(), anyString());
        verify(client, never()).post(anyString(), eq("/subscriptions"), any());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE details LIKE '%MAX_BOT_TOKEN%'", Integer.class))
                .isZero();
    }

    @Test
    void failedWebhookSubscriptionFromEnvironmentIsRetriedOnNextRun() throws Exception {
        String environmentToken = "env-bot-token-retry";
        clearStoredToken();
        when(client.get(environmentToken, "/me")).thenReturn(json("{\"user_id\":1,\"is_bot\":true,\"username\":\"env_bot\"}"));
        when(client.post(eq(environmentToken), eq("/subscriptions"), any()))
                .thenThrow(MaxException.unavailable("MAX не ответил: HttpTimeoutException"))
                .thenReturn(json("{\"success\":true}"));

        settingsService.bootstrap(environmentToken);
        assertThat(settingsRepository.find().subscribedAt()).isNull();
        assertThat(settingsRepository.find().subscriptionOk()).isFalse();

        settingsService.bootstrap(environmentToken);
        assertThat(settingsRepository.find().subscribedAt()).isNotNull();
        assertThat(settingsRepository.find().subscriptionOk()).isTrue();
    }

    @Test
    void stoppedBotStopsTheLinkAndLateOlderEventDoesNotRollBack() throws Exception {
        linkRepository.replaceLink(KAM, 777, "Анна", OffsetDateTime.now());
        outbox.enqueue("queued-1", KAM, 777, MaxMessageKind.ASSIGNMENT, null, MaxMessage.text("вуз назначен"));
        webhook(event("bot_stopped", 777, now(5)));
        webhook(botStarted(777, "Анна", null, now(2)));
        inbox.processBatch();
        assertThat(linkRepository.findByProfile(KAM).orElseThrow().status()).isEqualTo("STOPPED");
        assertThat(outboxStatus("queued-1")).isEqualTo("DROPPED");

        webhook(botStarted(777, "Анна", null, now(9)));
        inbox.processBatch();
        assertThat(linkRepository.findByProfile(KAM).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(replyTo(777)).contains(MaxLinkService.TEXT_RESUMED);
    }

    @Test
    void strangerGetsOnlyAFixedPhraseWithoutCrmData() throws Exception {
        webhook(botStarted(999, "Посторонний", null, now(1)));
        webhook("{\"update_type\":\"message_created\",\"timestamp\":" + now(2) + ",\"message\":{\"sender\":{\"user_id\":999,\"is_bot\":false},"
                + "\"recipient\":{\"chat_type\":\"dialog\"},\"body\":{\"mid\":\"mid-1\",\"text\":\"покажи вузы\"}}}");
        inbox.processBatch();
        assertThat(jdbcTemplate.queryForList("SELECT body FROM max_outbox WHERE max_user_id = 999", String.class))
                .hasSize(2)
                .allMatch(body -> body.contains(MaxLinkService.TEXT_STRANGER));
    }

    @Test
    void userAndAdministratorCanUnlinkAndSessionsAreClosed() throws Exception {
        linkRepository.replaceLink(KAM, 777, "Анна", OffsetDateTime.now());
        sessionRepository.insert("a".repeat(64), KAM, 777, 0, OffsetDateTime.now(), OffsetDateTime.now().plusHours(1));
        mockMvc.perform(delete("/api/max/link").with(login(KAM)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NONE"));
        assertThat(count("max_sessions")).isZero();

        linkRepository.replaceLink(KAM_2, 778, "Борис", OffsetDateTime.now());
        mockMvc.perform(get("/api/max/admin/links").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Борис КАМ"));
        mockMvc.perform(delete("/api/max/admin/links/{id}", KAM_2).with(login(ADMIN)).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("max_links")).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class)).contains("MAX_UNLINKED");
    }

    @Test
    void administratorEndingSessionsClosesMiniAppSessions() {
        sessionRepository.insert("b".repeat(64), KAM, 777, 0, OffsetDateTime.now(), OffsetDateTime.now().plusHours(1));
        events.publishEvent(new ProfileSessionsEnded(KAM));
        assertThat(count("max_sessions")).isZero();
    }

    @Test
    void messageIsNotSentWhenUniversityIsNoLongerVisible() {
        linkRepository.replaceLink(KAM, 777, "Анна", OffsetDateTime.now());
        when(organizationRepository.findVisibleById(any(), eq(ORGANIZATION))).thenReturn(Optional.empty());
        outbox.enqueue("assign-1", KAM, 777, MaxMessageKind.ASSIGNMENT, ORGANIZATION, MaxMessage.text("Вам назначен вуз"));
        outbox.sendBatch();
        assertThat(outboxStatus("assign-1")).isEqualTo("DROPPED");
        verify(client, never()).post(anyString(), anyString(), any());
    }

    @Test
    void messageIsNotSentToBlockedProfileOrWhenOutdated() {
        linkRepository.replaceLink(KAM, 777, "Анна", OffsetDateTime.now());
        linkRepository.replaceLink(KAM_2, 778, "Борис", OffsetDateTime.now());
        outbox.enqueue("blocked", KAM, 777, MaxMessageKind.DIGEST, null, MaxMessage.text("сводка"));
        outbox.enqueue("old", KAM_2, 778, MaxMessageKind.DIGEST, null, MaxMessage.text("сводка"));
        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", KAM);
        jdbcTemplate.update("UPDATE max_outbox SET expires_at = ? WHERE event_key = 'old'", OffsetDateTime.now().minusMinutes(1));
        outbox.sendBatch();
        assertThat(outboxStatus("blocked")).isEqualTo("DROPPED");
        assertThat(outboxStatus("old")).isEqualTo("DROPPED");
        verify(client, never()).post(anyString(), anyString(), any());
    }

    @Test
    void visibleUniversityMessageIsSentWithKeyboard() throws Exception {
        linkRepository.replaceLink(KAM, 777, "Анна", OffsetDateTime.now());
        when(organizationRepository.findVisibleById(any(), eq(ORGANIZATION))).thenReturn(Optional.of(organization()));
        outbox.enqueue("assign-2", KAM, 777, MaxMessageKind.ASSIGNMENT, ORGANIZATION,
                new MaxMessage("Вам назначен вуз «Колледж»", List.of(MaxMessage.Button.link("Открыть CRM", "https://crm.test/#/work"))));
        outbox.sendBatch();
        assertThat(outboxStatus("assign-2")).isEqualTo("SENT");
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("bot-token"), eq("/messages?user_id=777"), body.capture());
        String json = objectMapper.writeValueAsString(body.getValue());
        assertThat(json).contains("\"notify\"", "inline_keyboard", "Колледж", "\"type\":\"link\"");
    }

    @Test
    void forbiddenAnswerStopsTheLinkAndRateLimitIsRetried() {
        linkRepository.replaceLink(KAM, 777, "Анна", OffsetDateTime.now());
        linkRepository.replaceLink(KAM_2, 778, "Борис", OffsetDateTime.now());
        when(client.post(anyString(), eq("/messages?user_id=777"), any())).thenReturn(response(403));
        when(client.post(anyString(), eq("/messages?user_id=778"), any())).thenReturn(response(429));
        outbox.enqueue("denied", KAM, 777, MaxMessageKind.DIGEST, null, MaxMessage.text("сводка"));
        outbox.enqueue("limited", KAM_2, 778, MaxMessageKind.DIGEST, null, MaxMessage.text("сводка"));
        outbox.sendBatch();
        assertThat(outboxStatus("denied")).isEqualTo("DROPPED");
        assertThat(linkRepository.findByProfile(KAM).orElseThrow().status()).isEqualTo("STOPPED");
        assertThat(outboxStatus("limited")).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT next_attempt_at FROM max_outbox WHERE event_key = 'limited'", OffsetDateTime.class))
                .isAfter(OffsetDateTime.now());
    }

    @Test
    void disabledNotificationKindIsNotSent() {
        linkRepository.replaceLink(KAM, 777, "Анна", OffsetDateTime.now());
        linkRepository.savePreferences(KAM, new MaxLinkRepository.Preferences(false, true, true, true));
        outbox.enqueue("digest-off", KAM, 777, MaxMessageKind.DIGEST, null, MaxMessage.text("сводка"));
        outbox.sendBatch();
        assertThat(outboxStatus("digest-off")).isEqualTo("DROPPED");
    }

    @Test
    void quietHoursSendWithoutSound() {
        assertThat(outbox.quiet(OffsetDateTime.parse("2026-10-19T08:59:00+03:00"))).isTrue();
        assertThat(outbox.quiet(OffsetDateTime.parse("2026-10-19T09:00:00+03:00"))).isFalse();
        assertThat(outbox.quiet(OffsetDateTime.parse("2026-10-19T19:59:00+03:00"))).isFalse();
        assertThat(outbox.quiet(OffsetDateTime.parse("2026-10-19T20:00:00+03:00"))).isTrue();
    }

    private String requestLinkCode(UUID profile) throws Exception {
        String body = mockMvc.perform(post("/api/max/link").with(login(profile)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.startsWith("https://max.ru/rtk_bot?start=")))
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(body, "$.link");
        return link.substring(link.indexOf("start=") + "start=".length());
    }

    private ResultActions webhook(String event) throws Exception {
        return mockMvc.perform(post("/api/max/webhook").header(MaxWebhookController.SECRET_HEADER, secret)
                .contentType(MediaType.APPLICATION_JSON).content(event));
    }

    private List<String> replyTo(long maxUserId) {
        return jdbcTemplate.queryForList("SELECT body FROM max_outbox WHERE max_user_id = ? AND kind = 'REPLY'", String.class, maxUserId)
                .stream().map(body -> (String) JsonPath.read(body, "$.text")).toList();
    }

    private String outboxStatus(String key) {
        return jdbcTemplate.queryForObject("SELECT status FROM max_outbox WHERE event_key = ?", String.class, key);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static long now(int seconds) {
        return System.currentTimeMillis() + Duration.ofSeconds(seconds).toMillis();
    }

    private static String botStarted(long userId, String name, String payload, long timestamp) {
        return "{\"update_type\":\"bot_started\",\"timestamp\":" + timestamp + ",\"chat_id\":1,\"user\":{\"user_id\":" + userId
                + ",\"first_name\":\"" + name + "\",\"is_bot\":false}" + (payload == null ? "" : ",\"payload\":\"" + payload + "\"") + "}";
    }

    private static String event(String type, long userId, long timestamp) {
        return "{\"update_type\":\"" + type + "\",\"timestamp\":" + timestamp + ",\"chat_id\":1,\"user\":{\"user_id\":" + userId
                + ",\"first_name\":\"А\",\"is_bot\":false}}";
    }

    private static String callback(String callbackId, long timestamp) {
        return "{\"update_type\":\"message_callback\",\"timestamp\":" + timestamp + ",\"callback\":{\"callback_id\":\"" + callbackId
                + "\",\"timestamp\":" + timestamp + ",\"user\":{\"user_id\":777,\"first_name\":\"А\",\"is_bot\":false}}}";
    }

    private void clearStoredToken() {
        jdbcTemplate.update("""
                UPDATE max_settings SET token_encrypted = NULL, webhook_secret_encrypted = NULL, bot_name = NULL,
                    subscribed_at = NULL, subscription_ok = NULL, subscription_checked_at = NULL, subscription_error = NULL
                """);
        jdbcTemplate.update("DELETE FROM audit_events");
    }

    private MaxClient.Response json(String body) throws Exception {
        return new MaxClient.Response(200, objectMapper.readTree(body), Duration.ofSeconds(1));
    }

    private static MaxClient.Response response(int status) {
        return new MaxClient.Response(status, MissingNode.getInstance(), Duration.ofSeconds(1));
    }

    private static Organization organization() {
        return new Organization(ORGANIZATION, "Колледж", null, null, KAM, 0, null, "Анна КАМ", null, false, null, null, null, null,
                false, null, null);
    }

    private void insertProfile(UUID id, String name, String role, boolean operator) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, enrolment_operator)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, ISSUER, id.toString(), name, role, operator);
    }

    private RequestPostProcessor login(UUID profile) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profile.toString()));
    }
}
