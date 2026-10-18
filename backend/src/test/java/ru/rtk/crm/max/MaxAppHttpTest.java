package ru.rtk.crm.max;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.ProfileSessionsEnded;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementService;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionCommentRequest;
import ru.rtk.crm.interaction.InteractionCommentResult;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionPage;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionStepCompletionRequest;
import ru.rtk.crm.work.WorkAccessDeniedException;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.ReminderFeed;
import ru.rtk.crm.work.WorkService;

// Мини-приложение: вход по initData, короткий сеанс Bearer, отзыв сеанса, своя цепочка безопасности и тонкий
// контроллер над сервисами CRM (права проверяют сервисы, здесь они заменены заглушками).
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:max-app;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:ru/rtk/crm/max/base-schema.sql'",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-max-app-test",
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
class MaxAppHttpTest {
    private static final String BOT_TOKEN = "bot-token";
    private static final UUID ADMIN = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID KAM = UUID.fromString("81000000-0000-0000-0000-000000000002");
    private static final UUID OPERATOR = UUID.fromString("81000000-0000-0000-0000-000000000003");
    private static final UUID MANAGEMENT = UUID.fromString("81000000-0000-0000-0000-000000000004");
    private static final UUID INTERACTION = UUID.fromString("81000000-0000-0000-0000-000000000301");
    private static final UUID ORGANIZATION = UUID.fromString("81000000-0000-0000-0000-000000000201");
    private static final UUID STAGE = UUID.fromString("81000000-0000-0000-0000-000000000401");
    private static final UUID CONTACT = UUID.fromString("81000000-0000-0000-0000-000000000501");
    private static final UUID RESTRICTED_CONTACT = UUID.fromString("81000000-0000-0000-0000-000000000502");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MaxSettingsService settingsService;

    @Autowired
    private MaxLinkRepository linkRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @MockitoBean
    private MaxClient client;

    @MockitoBean
    private WorkService workService;

    @MockitoBean
    private InteractionService interactionService;

    @MockitoBean
    private OrganizationRepository organizationRepository;

    @MockitoBean
    private ContactService contactService;

    @MockitoBean
    private AgreementService agreementService;

    @BeforeEach
    void setUp() {
        for (String table : List.of("max_sessions", "max_outbox", "max_links", "audit_events", "crm_user_profiles")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        profile(ADMIN, "ADMIN", false);
        profile(KAM, "USER", false);
        profile(OPERATOR, "USER", true);
        profile(MANAGEMENT, "MANAGEMENT", false);
        settingsService.update(new CrmProfile(ADMIN, UserRole.ADMIN, null, 0), new MaxSettingsService.UpdateRequest(BOT_TOKEN, null), "t");
        OffsetDateTime now = OffsetDateTime.now();
        linkRepository.replaceLink(KAM, 777, "Анна", now);
        linkRepository.replaceLink(OPERATOR, 778, "Оператор", now);
        linkRepository.replaceLink(MANAGEMENT, 779, "Руководство", now);
        linkRepository.replaceLink(ADMIN, 780, "Админ", now);
        when(workService.reminders(any())).thenReturn(new ReminderDigest(true, now, 7, 1, 2, 3, List.of(), List.of(), List.of(),
                List.of(), 2027, List.of(), 90, List.of(), new ReminderFeed(1, 2, 3, 4, 0, 0, 0)));
        when(interactionService.list(any(), any(), any())).thenReturn(new InteractionPage(List.of(), 0, 20, 137));
        when(interactionService.get(any(), eq(INTERACTION))).thenReturn(interaction());
    }

    @Test
    void validInitDataOpensSessionAndOnlyItsHashIsStored() throws Exception {
        String token = session(777);
        assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM max_sessions", String.class))
                .isEqualTo(MaxCodes.sha256(token)).doesNotContain(token);
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class)).contains("MAX_SESSION_STARTED");
    }

    @Test
    void forgedOrStaleInitDataIsRejected() throws Exception {
        String forged = initData(777, now()).replace("%3A777", "%3A999");
        start(forged).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MAX_INIT_DATA_INVALID"));
        start(initData(777, now(), "another-token")).andExpect(status().isUnauthorized());
        start(initData(777, now() - 2 * 3600)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MAX_INIT_DATA_EXPIRED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM max_sessions", Integer.class)).isZero();
    }

    @Test
    void unlinkedAccountAndNotAllowedRolesGetNoSession() throws Exception {
        start(initData(999, now())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAX_NOT_LINKED"));
        for (long user : List.of(778L, 779L, 780L)) {
            start(initData(user, now())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAX_ROLE_NOT_ALLOWED"));
        }
        jdbcTemplate.update("UPDATE max_links SET status = 'STOPPED' WHERE profile_id = ?", KAM);
        start(initData(777, now())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAX_NOT_LINKED"));
    }

    @Test
    void workUsesServerFeedAndPagesForTheSessionProfile() throws Exception {
        String token = session(777);
        mockMvc.perform(get("/api/max/app/work").param("due", "OVERDUE").param("page", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.overdue").value(1))
                .andExpect(jsonPath("$.items.total").value(137))
                .andExpect(jsonPath("$.due").value("OVERDUE"));
        ArgumentCaptor<CrmProfile> profile = ArgumentCaptor.forClass(CrmProfile.class);
        verify(workService).reminders(profile.capture());
        assertThat(profile.getValue().id()).isEqualTo(KAM);
        mockMvc.perform(get("/api/max/app/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.canControl").value(false));
    }

    @Test
    void requestsWithoutValidBearerAreRejectedAndCookieSessionDoesNotOpenTheApp() throws Exception {
        mockMvc.perform(get("/api/max/app/work")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MAX_SESSION_EXPIRED"));
        mockMvc.perform(get("/api/max/app/work").header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/max/app/work").with(oidcLogin())).andExpect(status().is4xxClientError());
        verify(workService, never()).reminders(any());
        String token = session(777);
        mockMvc.perform(get("/api/reminders").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionIsRevokedByAccessRevisionAdminEndSessionsUnlinkStopAndIdle() throws Exception {
        String token = session(777);
        jdbcTemplate.update("UPDATE crm_user_profiles SET access_revision = access_revision + 1 WHERE id = ?", KAM);
        expectRevoked(token);

        jdbcTemplate.update("UPDATE crm_user_profiles SET access_revision = 0 WHERE id = ?", KAM);
        token = session(777);
        events.publishEvent(new ProfileSessionsEnded(KAM));
        expectRevoked(token);

        token = session(777);
        jdbcTemplate.update("UPDATE max_links SET status = 'STOPPED' WHERE profile_id = ?", KAM);
        expectRevoked(token);

        jdbcTemplate.update("UPDATE max_links SET status = 'ACTIVE' WHERE profile_id = ?", KAM);
        token = session(777);
        jdbcTemplate.update("UPDATE max_sessions SET last_seen_at = ?", OffsetDateTime.now().minusMinutes(31));
        expectRevoked(token);

        token = session(777);
        jdbcTemplate.update("UPDATE max_sessions SET expires_at = ?", OffsetDateTime.now().minusSeconds(1));
        expectRevoked(token);

        token = session(777);
        jdbcTemplate.update("UPDATE crm_user_profiles SET active = FALSE WHERE id = ?", KAM);
        expectRevoked(token);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String token = session(777);
        mockMvc.perform(delete("/api/max/app/session").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        expectRevoked(token);
    }

    @Test
    void commentRequiresTheWholeNextStepAndKeepsTheCurrentStage() throws Exception {
        String token = session(777);
        mockMvc.perform(post("/api/max/app/interactions/{id}/comments", INTERACTION).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"text\":\"Встретились\",\"nextStep\":{\"nextAction\":\"Отправить договор\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.nextStep").exists());
        verify(interactionService, never()).comment(any(), any(), any(), any());

        when(interactionService.comment(any(), eq(INTERACTION), any(), eq("k-1")))
                .thenReturn(new InteractionCommentResult(interaction(), null));
        mockMvc.perform(post("/api/max/app/interactions/{id}/comments", INTERACTION).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"text\":\"Встретились\",\"nextStep\":{\"nextAction\":\"Отправить договор\",\"nextActionAt\":\"2026-10-21T10:00:00+03:00\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(INTERACTION.toString()));
        ArgumentCaptor<InteractionCommentRequest> request = ArgumentCaptor.forClass(InteractionCommentRequest.class);
        verify(interactionService).comment(any(), eq(INTERACTION), request.capture(), eq("k-1"));
        assertThat(request.getValue().stageId()).isEqualTo(STAGE);
        assertThat(request.getValue().nextStep().nextAction()).isEqualTo("Отправить договор");
        assertThat(request.getValue().nextStep().nextActionAt()).isNotNull();
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class)).contains("MAX_CHANGE");
    }

    @Test
    void stepCompletionPassesResultAndNewStep() throws Exception {
        String token = session(777);
        when(interactionService.completeStep(any(), eq(INTERACTION), any(), eq("k-2"))).thenReturn(interaction());
        mockMvc.perform(post("/api/max/app/interactions/{id}/step-completions", INTERACTION).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "k-2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"result\":\"Договор отправлен\",\"nextStep\":{\"nextAction\":\"Получить подпись\",\"nextActionAt\":\"2026-10-25T10:00:00+03:00\"}}"))
                .andExpect(status().isOk());
        ArgumentCaptor<InteractionStepCompletionRequest> request = ArgumentCaptor.forClass(InteractionStepCompletionRequest.class);
        verify(interactionService).completeStep(any(), eq(INTERACTION), request.capture(), eq("k-2"));
        assertThat(request.getValue().result()).isEqualTo("Договор отправлен");
        assertThat(request.getValue().version()).isEqualTo(3);
    }

    @Test
    void cardShowsLinkedActiveContactsWithPhonesAndAgreements() throws Exception {
        String token = session(777);
        when(organizationRepository.findVisibleById(any(), eq(ORGANIZATION))).thenReturn(java.util.Optional.of(new Organization(
                ORGANIZATION, "Колледж связи", null, null, KAM, 0, null, "Анна КАМ", null, false, null, null, null, null, false, null, null)));
        when(interactionService.events(any(), eq(INTERACTION))).thenReturn(List.of());
        when(contactService.list(any(), eq(ORGANIZATION))).thenReturn(List.of(
                contact(CONTACT, "Иван Петров", "+7 900 000-00-00", PersonalDataStatus.ACTIVE),
                contact(RESTRICTED_CONTACT, "Скрытый", "+7 900 111-11-11", PersonalDataStatus.RESTRICTED),
                contact(UUID.randomUUID(), "Не из карточки", "+7 900 222-22-22", PersonalDataStatus.ACTIVE)));
        when(agreementService.list(any(), eq(ORGANIZATION))).thenReturn(List.of(new AgreementSummary(UUID.randomUUID(), ORGANIZATION, "Д-1",
                null, LocalDate.of(2027, 1, 1), null, null, null, AgreementStatus.ACTIVE, null, 0, 0, 0, List.of())));
        mockMvc.perform(get("/api/max/app/interactions/{id}", INTERACTION).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.name").value("Колледж связи"))
                .andExpect(jsonPath("$.contacts.length()").value(1))
                .andExpect(jsonPath("$.contacts[0].phone").value("+7 900 000-00-00"))
                .andExpect(jsonPath("$.agreements[0].statusTitle").value("Действует"));
    }

    @Test
    void foreignCardAndControlForKamAreRefusedByServices() throws Exception {
        String token = session(777);
        UUID foreign = UUID.randomUUID();
        when(interactionService.get(any(), eq(foreign))).thenThrow(new InteractionNotFoundException());
        mockMvc.perform(get("/api/max/app/interactions/{id}", foreign).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        when(workService.teamIndicators(any(), any())).thenThrow(new WorkAccessDeniedException());
        mockMvc.perform(get("/api/max/app/control").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private void expectRevoked(String token) throws Exception {
        mockMvc.perform(get("/api/max/app/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MAX_SESSION_EXPIRED"));
    }

    private String session(long maxUserId) throws Exception {
        String body = start(initData(maxUserId, now())).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private ResultActions start(String initData) throws Exception {
        return mockMvc.perform(post("/api/max/app/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"" + initData + "\"}"));
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    static String initData(long userId, long authDate) {
        return initData(userId, authDate, BOT_TOKEN);
    }

    static String initData(long userId, long authDate, String token) {
        Map<String, String> pairs = new TreeMap<>(Map.of(
                "auth_date", String.valueOf(authDate),
                "query_id", "q-1",
                "user", "{\"id\":" + userId + ",\"first_name\":\"Анна\"}",
                "start_param", "work"));
        String hash = MaxInitData.sign(pairs, token);
        return pairs.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")) + "&hash=" + hash;
    }

    private void profile(UUID id, String role, boolean operator) {
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, enrolment_operator) VALUES (?, 'i', ?, ?, ?, ?)",
                id, id.toString(), role, role, operator);
    }

    private static Interaction interaction() {
        OffsetDateTime now = OffsetDateTime.now();
        return new Interaction(INTERACTION, ORGANIZATION, "Встреча", STAGE, "Первичный контакт", List.of(), List.of(), List.of(),
                "Позвонить", now.plusDays(1), List.of(CONTACT, RESTRICTED_CONTACT), null, null, List.of(), List.of(), List.of(), List.of(),
                null, 3, KAM, now, now, null, false, null);
    }

    private static Contact contact(UUID id, String name, String phone, PersonalDataStatus status) {
        return new Contact(id, ORGANIZATION, name, "Декан", null, phone, 0, KAM, OffsetDateTime.now(), OffsetDateTime.now(), null,
                false, false, null, null, null, status);
    }
}
