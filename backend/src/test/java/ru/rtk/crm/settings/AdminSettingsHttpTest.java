package ru.rtk.crm.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.robustness.H2Schema;

// Пакет w59: экран «Настройки», нормативы этапов, журнал обращений субъектов ПДн, согласия, состояние модулей и
// сводка версий маршрута. Схема — настоящие миграции на H2: таблицы V70 и сброс правки проверяются как на сервере.
// Главное правило настроек — значение действует без перезапуска: каждая проверка «до/после» идёт в том же контексте.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.session.jdbc.initialize-schema=never",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-admin-settings-test",
        "app.reports.storage-root=${java.io.tmpdir}/rtk-crm-admin-settings-reports",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.api-rate-limit.per-minute=0",
        "app.api-rate-limit.anonymous-per-minute=0",
        "app.modules.mail.enabled=true",
        "app.mail.jobs-enabled=false",
        "app.modules.enrolment.enabled=true",
        "app.enrolment.active-key-version=v1",
        "app.enrolment.keys.v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.enrolment.fingerprint-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("oidc")
class AdminSettingsHttpTest {
    private static final String DATABASE = "jdbc:h2:mem:admin-settings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000059a1");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-0000000059a2");
    private static final UUID KAM = UUID.fromString("00000000-0000-0000-0000-0000000059a3");
    private static final UUID MANAGEMENT = UUID.fromString("00000000-0000-0000-0000-0000000059a4");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000059b1");
    private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-0000000059c1");
    private static final UUID CONTACT = UUID.fromString("00000000-0000-0000-0000-0000000059d1");
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-0000000059a5");
    private static final UUID LEARNER = UUID.fromString("00000000-0000-0000-0000-0000000059e1");
    private static final UUID BASE_TEMPLATE = UUID.fromString("00000000-0000-0000-0000-000000000801");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL(DATABASE);
        dataSource.setUser("sa");
        JdbcTemplate schema = new JdbcTemplate(dataSource);
        Integer tables = schema.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ORGANIZATIONS'", Integer.class);
        if (tables == null || tables == 0) {
            H2Schema.apply(schema);
        }
        registry.add("spring.datasource.url", () -> DATABASE);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM system_settings");
        jdbc.update("DELETE FROM personal_data_requests");
        jdbc.update("DELETE FROM personal_data_consents");
        jdbc.update("DELETE FROM mail_outbox");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("UPDATE workflow_template_stages SET norm_days = NULL");
        // Версии маршрута на H2 не создаются миграцией (json_build_object), без версии работа не помнит этапы шаблона.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM workflow_template_versions WHERE template_id = ?", Integer.class, BASE_TEMPLATE) == 0) {
            version(1);
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM crm_user_profiles WHERE id = ?", Integer.class, ADMIN) > 0) {
            return;
        }
        jdbc.update("INSERT INTO teams (id, name) VALUES (?, 'Команда настроек')", TEAM);
        profile(ADMIN, UserRole.ADMIN, null);
        profile(LEADER, UserRole.LEADER, TEAM);
        profile(KAM, UserRole.USER, TEAM);
        profile(MANAGEMENT, UserRole.MANAGEMENT, null);
        profile(OPERATOR, UserRole.USER, TEAM);
        jdbc.update("UPDATE crm_user_profiles SET enrolment_operator = TRUE, display_name = 'МУП' WHERE id = ?", OPERATOR);
        jdbc.update("INSERT INTO learners (id, key_version, fields) VALUES (?, 'v1', 'зашифровано')", LEARNER);
        jdbc.update("INSERT INTO organizations (id, name, type, team_id, owner_manager_id) VALUES (?, 'Университет настроек', 'UNIVERSITY', ?, ?)",
                ORGANIZATION, TEAM, KAM);
        jdbc.update("INSERT INTO contacts (id, organization_id, name, email, created_by) VALUES (?, ?, 'Иван Петров', 'ivan@example.test', ?)",
                CONTACT, ORGANIZATION, KAM);
    }

    @Test
    void landingPageModeIsPublicReadOnlyChangedOnlyByAdministratorAndJournaled() throws Exception {
        // Без входа виден только режим главной и модули — других настроек публичный ответ не раскрывает.
        String anonymous = mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.landing").value("CONTEST"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(anonymous).fieldNames()).toIterable().containsExactlyInAnyOrder("modules", "landing");

        mockMvc.perform(put("/api/admin/settings/LANDING_PAGE").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"value\":1}"))
                .andExpect(status().isUnauthorized());
        for (UUID profile : List.of(KAM, LEADER, MANAGEMENT)) {
            mockMvc.perform(json(put("/api/admin/settings/LANDING_PAGE"), profile, "{\"value\":1}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/config")).andExpect(jsonPath("$.landing").value("CONTEST"));
        mockMvc.perform(json(put("/api/admin/settings/LANDING_PAGE"), ADMIN, "{\"value\":2}")).andExpect(status().isBadRequest());

        mockMvc.perform(json(put("/api/admin/settings/LANDING_PAGE"), ADMIN, "{\"value\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'LANDING_PAGE')].group").value(hasItem("SITE")))
                .andExpect(jsonPath("$[?(@.key == 'LANDING_PAGE')].unit").value(hasItem("LANDING")))
                .andExpect(jsonPath("$[?(@.key == 'LANDING_PAGE')].value").value(hasItem(1)));
        mockMvc.perform(get("/api/config")).andExpect(jsonPath("$.landing").value("PRODUCT"));

        mockMvc.perform(delete("/api/admin/settings/LANDING_PAGE").with(login(ADMIN)).with(csrf())).andExpect(status().isOk());
        mockMvc.perform(get("/api/config")).andExpect(jsonPath("$.landing").value("CONTEST"));
        assertThat(jdbc.queryForList("""
                SELECT object_name || ': ' || details FROM audit_events WHERE action = 'SYSTEM_SETTING_CHANGED'""", String.class))
                .containsExactlyInAnyOrder(
                        "Главная страница: конкурсная → продуктовая",
                        "Главная страница: возвращено значение конфигурации: продуктовая → конкурсная");
    }

    @Test
    void onlyAdministratorChangesSettingsEachChangeIsJournaledAndActsWithoutRestart() throws Exception {
        for (UUID profile : List.of(KAM, LEADER, MANAGEMENT)) {
            mockMvc.perform(get("/api/admin/settings").with(login(profile))).andExpect(status().isForbidden());
            mockMvc.perform(json(put("/api/admin/settings/STUCK_DAYS"), profile, "{\"value\":5}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/settings").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'STUCK_DAYS')].value").value(hasItem(30)))
                .andExpect(jsonPath("$[?(@.key == 'STUCK_DAYS')].defaultValue").value(hasItem(30)))
                .andExpect(jsonPath("$[?(@.key == 'RETENTION_LEARNER_PROFILES')].value").value(hasItem(36)))
                .andExpect(jsonPath("$[?(@.key == 'SOURCES_STALE_HOURS')].value").value(hasItem(26)))
                .andExpect(jsonPath("$[?(@.key == 'CONSENTS_CONTACTS')].value").value(hasItem(0)));
        mockMvc.perform(json(put("/api/admin/settings/STUCK_DAYS"), ADMIN, "{\"value\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(json(put("/api/admin/settings/NO_SUCH"), ADMIN, "{\"value\":5}")).andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/api/admin/settings/STUCK_DAYS"), ADMIN, "{}")).andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/work/team-indicators").with(login(LEADER))).andExpect(jsonPath("$.stuckDays").value(30));
        mockMvc.perform(json(put("/api/admin/settings/STUCK_DAYS"), ADMIN, "{\"value\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'STUCK_DAYS')].saved").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.key == 'STUCK_DAYS')].updatedByName").value(hasItem("Администратор")));
        mockMvc.perform(get("/api/work/team-indicators").with(login(LEADER))).andExpect(jsonPath("$.stuckDays").value(12));

        mockMvc.perform(json(put("/api/admin/settings/RETENTION_REPORT_FILES"), ADMIN, "{\"value\":14}")).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/admin/settings/RETENTION_LEARNER_PROFILES"), ADMIN, "{\"value\":18}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/retention").with(login(ADMIN)))
                .andExpect(jsonPath("$.reportFilesDays").value(14))
                .andExpect(jsonPath("$.learnerProfilesTerm").value("1 г. 6 мес."));
        mockMvc.perform(json(put("/api/admin/settings/SOURCES_STALE_HOURS"), ADMIN, "{\"value\":48}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/learning-coverage").with(login(ADMIN))).andExpect(jsonPath("$.staleAfterHours").value(48));

        mockMvc.perform(delete("/api/admin/settings/STUCK_DAYS").with(login(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'STUCK_DAYS')].value").value(hasItem(30)))
                .andExpect(jsonPath("$[?(@.key == 'STUCK_DAYS')].saved").value(hasItem(false)));
        mockMvc.perform(get("/api/work/team-indicators").with(login(LEADER))).andExpect(jsonPath("$.stuckDays").value(30));
        assertThat(jdbc.queryForList("""
                SELECT object_name || ': ' || details FROM audit_events WHERE action = 'SYSTEM_SETTING_CHANGED'
                ORDER BY occurred_at""", String.class))
                // Порядок не проверяется: на Windows часы шагают по миллисекунде, и записи одного шага могут совпасть по времени.
                .containsExactlyInAnyOrder(
                        "«Долго на этапе», если у этапа нет норматива: 30 дн. → 12 дн.",
                        "Файлы заказанных отчётов: 7 дн. → 14 дн.",
                        "Анкеты слушателей после окончания потока: 36 мес. → 18 мес.",
                        "Данные LMS и источников устарели через: 26 ч → 48 ч",
                        "«Долго на этапе», если у этапа нет норматива: возвращено значение конфигурации: 12 дн. → 30 дн.");
    }

    @Test
    void stageNormMakesWorkStuckBeforeTheGeneralThresholdAndAppliesToRunningWorksAtOnce() throws Exception {
        UUID interaction = createInteraction("Норматив этапа");
        OffsetDateTime tenDaysAgo = OffsetDateTime.now().minusDays(10);
        jdbc.update("UPDATE interactions SET created_at = ? WHERE id = ?", tenDaysAgo, interaction);
        jdbc.update("UPDATE interaction_events SET occurred_at = ? WHERE interaction_id = ?", tenDaysAgo, interaction);
        UUID templateStage = jdbc.queryForObject("""
                SELECT s.template_stage_id FROM interactions i JOIN interaction_stages s ON s.id = i.current_stage_id
                WHERE i.id = ?""", UUID.class, interaction);
        assertThat(templateStage).isNotNull();

        mockMvc.perform(get("/api/work/team-indicators").with(login(LEADER)))
                .andExpect(jsonPath("$.managers[?(@.managerId == '" + KAM + "')].stuck").value(hasItem(0)));
        // Норматив правится в шаблоне; идущая работа подхватывает его без перевода на новую версию.
        jdbc.update("UPDATE workflow_template_stages SET norm_days = 5 WHERE id = ?", templateStage);
        mockMvc.perform(get("/api/work/team-indicators").with(login(LEADER)))
                .andExpect(jsonPath("$.managers[?(@.managerId == '" + KAM + "')].stuck").value(hasItem(1)));
        mockMvc.perform(get("/api/work/control").with(login(LEADER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..works[?(@.interactionId == '" + interaction + "')].stageNormDays").value(hasItem(5)));
        mockMvc.perform(json(post("/api/reports/preview"), LEADER, """
                        {"kind":"MANAGER_WORK","filters":{"tile":"STUCK"},"columns":["ORGANIZATION","INTERACTION"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.notes[*]", hasItem(containsString("дольше норматива этапа, а без норматива — дольше 30 дн."))));

        // Норматив длиннее срока на этапе: работа не «долго на этапе», хотя общий порог ниже.
        jdbc.update("UPDATE workflow_template_stages SET norm_days = 20 WHERE id = ?", templateStage);
        mockMvc.perform(json(put("/api/admin/settings/STUCK_DAYS"), ADMIN, "{\"value\":3}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/work/team-indicators").with(login(LEADER)))
                .andExpect(jsonPath("$.managers[?(@.managerId == '" + KAM + "')].stuck").value(hasItem(0)));
    }

    @Test
    void personalDataRequestsAreRegisteredWithDueDateLinkedToExecutionRemindedAndExported() throws Exception {
        LocalDate today = LocalDate.now(MOSCOW);
        mockMvc.perform(get("/api/admin/personal-data/requests").with(login(LEADER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reminders").with(login(KAM))).andExpect(status().isForbidden());
        mockMvc.perform(json(post("/api/admin/personal-data/requests"), ADMIN, "{\"subjectName\":\"Иван\"}"))
                .andExpect(status().isBadRequest());

        String created = mockMvc.perform(json(post("/api/admin/personal-data/requests"), ADMIN, """
                        {"receivedOn":"%s","subjectName":"Иван Петров","kind":"INFORMATION","channel":"письмо на почту школы"}"""
                        .formatted(today.minusDays(9))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueOn").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.assigneeName").value("Администратор"))
                .andExpect(jsonPath("$.dueState").value("SOON"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID request = UUID.fromString(objectMapper.readTree(created).path("id").asText());

        // Срок ответа по умолчанию — настройка эксплуатанта.
        mockMvc.perform(json(put("/api/admin/settings/PERSONAL_DATA_RESPONSE_DAYS"), ADMIN, "{\"value\":30}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/admin/personal-data/requests"), ADMIN, """
                        {"receivedOn":"%s","subjectName":"Пётр Сидоров","kind":"DELETION","channel":"лично"}""".formatted(today)))
                .andExpect(jsonPath("$.dueOn").value(today.plusDays(30).toString()))
                .andExpect(jsonPath("$.dueState").doesNotExist());

        mockMvc.perform(get("/api/admin/reminders").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalDataRequests", hasSize(1)))
                .andExpect(jsonPath("$.personalDataRequests[0].id").value(request.toString()));

        UUID export = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_events (id, category, action, actor_profile_id, actor_display_name, details, occurred_at)
                VALUES (?, 'PERSONAL_DATA', 'SUBJECT_EXPORTED', ?, 'Администратор', 'формат PDF', ?)""", export, ADMIN, OffsetDateTime.now());
        mockMvc.perform(get("/api/admin/personal-data/requests").with(login(ADMIN)))
                .andExpect(jsonPath("$.executions[*].auditEventId").value(hasItem(export.toString())))
                .andExpect(jsonPath("$.assignees[*].id").value(hasItem(ADMIN.toString())))
                .andExpect(jsonPath("$.assignees[*].id").value(not(hasItem(KAM.toString()))))
                .andExpect(jsonPath("$.workingDays").value(false))
                .andExpect(jsonPath("$.responseDays").value(30));
        mockMvc.perform(json(put("/api/admin/personal-data/requests/" + request), ADMIN, """
                        {"receivedOn":"%s","subjectName":"Иван Петров","kind":"INFORMATION","channel":"письмо на почту школы",
                         "status":"DONE","auditEventId":"%s"}""".formatted(today.minusDays(9), UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        // Исполнитель выбирается из действующих администраторов.
        mockMvc.perform(json(put("/api/admin/personal-data/requests/" + request), ADMIN, """
                        {"receivedOn":"%s","subjectName":"Иван Петров","kind":"INFORMATION","channel":"письмо на почту школы",
                         "assigneeId":"%s"}""".formatted(today.minusDays(9), KAM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.assigneeId").exists());
        mockMvc.perform(json(put("/api/admin/personal-data/requests/" + request), ADMIN, """
                        {"receivedOn":"%s","subjectName":"Иван Петров","kind":"INFORMATION","channel":"письмо на почту школы",
                         "status":"DONE","auditEventId":"%s"}""".formatted(today.minusDays(9), export)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedOn").value(today.toString()))
                .andExpect(jsonPath("$.execution.label").value("Выгружены сведения о субъекте ПДн"))
                .andExpect(jsonPath("$.dueOn").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.dueState").doesNotExist());
        mockMvc.perform(get("/api/admin/reminders").with(login(ADMIN))).andExpect(jsonPath("$.personalDataRequests", hasSize(0)));

        mockMvc.perform(get("/api/admin/personal-data/requests/export").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"Иван Петров\";\"сведения об обработке\"")))
                .andExpect(content().string(containsString("Выгружены сведения о субъекте ПДн")));
        List<String> journal = jdbc.queryForList("""
                SELECT details FROM audit_events WHERE action IN ('PERSONAL_DATA_REQUEST_CHANGED', 'PERSONAL_DATA_REQUESTS_EXPORTED')
                ORDER BY occurred_at""", String.class);
        assertThat(journal).hasSize(4).noneMatch(line -> line.contains("Иван") || line.contains("Пётр"));
        assertThat(journal).anyMatch(line -> line.contains("статус: новое → исполнено")
                && line.contains("исполнение: не указано → Выгружены сведения о субъекте ПДн"));

        // Закрытое обращение удаляется по сроку хранения после исполнения.
        jdbc.update("UPDATE personal_data_requests SET received_on = ?, due_on = ?, completed_on = ? WHERE id = ?",
                today.minusDays(400), today.minusDays(390), today.minusDays(380), request);
        mockMvc.perform(json(put("/api/admin/settings/RETENTION_PERSONAL_DATA_REQUESTS"), ADMIN, "{\"value\":365}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/retention/run").with(login(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalDataRequestsDeleted").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_data_requests", Integer.class)).isEqualTo(1);
    }

    @Test
    void kamRecordsContactConsentStudyManagerRecordsLearnerConsentAndAdministratorOnlySeesThem() throws Exception {
        String contactPath = "/api/organizations/" + ORGANIZATION + "/contacts/" + CONTACT + "/consents";
        String learnerPath = "/api/enrolment/learners/" + LEARNER + "/consents";
        String consent = """
                {"givenOn":"%s","basis":"согласие на обработку ПДн","document":"№ 12"}""".formatted(LocalDate.now(MOSCOW).minusDays(3));
        mockMvc.perform(get("/api/consent-settings").with(login(KAM)))
                .andExpect(jsonPath("$.contacts").value(false)).andExpect(jsonPath("$.learners").value(false));
        mockMvc.perform(get(contactPath).with(login(KAM))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(json(post(contactPath), KAM, consent)).andExpect(status().isNotFound());

        mockMvc.perform(json(put("/api/admin/settings/CONSENTS_CONTACTS"), ADMIN, "{\"value\":1}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/consent-settings").with(login(KAM))).andExpect(jsonPath("$.contacts").value(true));
        // Права — как на правку контакта: «Руководство» и администратор только видят.
        mockMvc.perform(json(post(contactPath), MANAGEMENT, consent)).andExpect(status().isForbidden());
        mockMvc.perform(json(post(contactPath), ADMIN, consent)).andExpect(status().isNotFound());
        String created = mockMvc.perform(json(post(contactPath), KAM, consent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("согласие на обработку ПДн"))
                .andExpect(jsonPath("$.authorName").value("КАМ"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(objectMapper.readTree(created).path("id").asText());
        String withdrawal = """
                {"givenOn":"%s","basis":"согласие на обработку ПДн","document":"№ 12","withdrawnOn":"%s"}""";
        mockMvc.perform(json(put(contactPath + "/" + id), KAM,
                        withdrawal.formatted(LocalDate.now(MOSCOW).minusDays(3), LocalDate.now(MOSCOW).minusDays(5))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put(contactPath + "/" + id), LEADER,
                        withdrawal.formatted(LocalDate.now(MOSCOW).minusDays(3), LocalDate.now(MOSCOW))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawnOn").value(LocalDate.now(MOSCOW).toString()));
        mockMvc.perform(get(contactPath).with(login(MANAGEMENT))).andExpect(jsonPath("$.consents", hasSize(1)));
        mockMvc.perform(get("/api/admin/personal-data/consents").param("subjectType", "CONTACT").param("subjectId", CONTACT.toString())
                        .with(login(ADMIN)))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.consents", hasSize(1)));
        mockMvc.perform(get("/api/admin/personal-data/consents").param("subjectType", "CONTACT").param("subjectId", CONTACT.toString())
                        .with(login(KAM)))
                .andExpect(status().isForbidden());
        // Ограниченный контакт не меняется.
        jdbc.update("UPDATE contacts SET personal_data_status = 'RESTRICTED' WHERE id = ?", CONTACT);
        mockMvc.perform(json(post(contactPath), KAM, consent))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERSONAL_DATA_RESTRICTED"));
        jdbc.update("UPDATE contacts SET personal_data_status = 'ACTIVE' WHERE id = ?", CONTACT);

        // Анкету слушателя — менеджер учебного процесса, учёт слушателей включается отдельно.
        mockMvc.perform(json(post(learnerPath), OPERATOR, consent)).andExpect(status().isNotFound());
        mockMvc.perform(json(put("/api/admin/settings/CONSENTS_LEARNERS"), ADMIN, "{\"value\":1}")).andExpect(status().isOk());
        mockMvc.perform(json(post(learnerPath), KAM, consent)).andExpect(status().isForbidden());
        mockMvc.perform(json(post(learnerPath), OPERATOR, consent)).andExpect(status().isOk());
        mockMvc.perform(get(learnerPath).with(login(OPERATOR))).andExpect(jsonPath("$.consents", hasSize(1)));
        mockMvc.perform(json(put(contactPath + "/" + id), KAM,
                        withdrawal.formatted(LocalDate.now(MOSCOW).minusDays(3), LocalDate.now(MOSCOW))))
                .andExpect(status().isOk());
        mockMvc.perform(json(put(learnerPath + "/" + id), OPERATOR,
                        withdrawal.formatted(LocalDate.now(MOSCOW).minusDays(3), LocalDate.now(MOSCOW))))
                .andExpect(status().isNotFound());

        List<String> journal = jdbc.queryForList(
                "SELECT object_name || ': ' || details FROM audit_events WHERE action = 'CONSENT_CHANGED' ORDER BY occurred_at", String.class);
        assertThat(journal).hasSize(4).noneMatch(line -> line.contains("Иван"));
        assertThat(journal).anyMatch(line -> line.startsWith("контакт " + CONTACT) && line.contains("согласие отозвано"));
    }

    @Test
    void moduleStatusShowsSwitchesVariablesAndHealthToTheAdministratorOnly() throws Exception {
        mockMvc.perform(get("/api/admin/modules").with(login(MANAGEMENT))).andExpect(status().isForbidden());
        OffsetDateTime sent = OffsetDateTime.now().minusHours(2);
        mail("SENT", sent, null);
        mail("ERROR", OffsetDateTime.now().minusHours(1), "SMTP 535");
        String body = mockMvc.perform(get("/api/admin/modules").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("MAX", "MCP", "MAIL", "MOODLE_WRITE", "ENROLMENT", "PARTNER")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, JsonNode> modules = new java.util.HashMap<>();
        objectMapper.readTree(body).forEach(node -> modules.put(node.path("code").asText(), node));
        assertThat(modules.get("MAX").path("enabled").asBoolean()).isFalse();
        assertThat(modules.get("MAX").path("variable").asText()).isEqualTo("MAX_ENABLED");
        assertThat(modules.get("MAIL").path("enabled").asBoolean()).isTrue();
        assertThat(modules.get("MAIL").path("errors").asLong()).isEqualTo(1);
        assertThat(modules.get("MAIL").path("lastError").asText()).isEqualTo("SMTP 535");
        assertThat(OffsetDateTime.parse(modules.get("MAIL").path("lastSuccessAt").asText()).toInstant())
                .isCloseTo(sent.toInstant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(modules.get("MAIL").path("lastSuccess").asText()).isEqualTo("последняя доставка");

        // Режим «Без отправки»: письмо только записано в журнал — это не доставка.
        mail("SENT", OffsetDateTime.now().minusMinutes(30), null);
        jdbc.update("UPDATE mail_outbox SET transport = 'STUB' WHERE sent_at = (SELECT MAX(sent_at) FROM mail_outbox)");
        String stub = mockMvc.perform(get("/api/admin/modules").with(login(ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        objectMapper.readTree(stub).forEach(node -> modules.put(node.path("code").asText(), node));
        assertThat(modules.get("MAIL").path("lastSuccess").asText()).contains("в журнал без отправки").doesNotContain("доставка");
    }

    @Test
    void routeVersionSummaryShowsEveryTeamToAdministratorAndOwnTeamToTheLeader() throws Exception {
        UUID interaction = createInteraction("Версия маршрута");
        jdbc.update("UPDATE interactions SET workflow_template_id = ?, workflow_version = 1 WHERE id = ?", BASE_TEMPLATE, interaction);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM workflow_template_versions WHERE template_id = ? AND version_number = 2",
                Integer.class, BASE_TEMPLATE) == 0) {
            version(2);
        }

        mockMvc.perform(get("/api/workflow-route-versions").with(login(KAM))).andExpect(status().isForbidden());
        for (UUID profile : List.of(ADMIN, MANAGEMENT, LEADER)) {
            mockMvc.perform(get("/api/workflow-route-versions").with(login(profile)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.teamId == '" + TEAM + "')].latestVersion").value(hasItem(2)))
                    .andExpect(jsonPath("$[?(@.teamId == '" + TEAM + "')].outdatedWorks").value(hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(1))))
                    .andExpect(jsonPath("$[*].teamId").value(not(hasItem("0e7e0000-0000-4000-8000-000000000001"))));
        }
    }

    private void version(int number) {
        jdbc.update("""
                INSERT INTO workflow_template_versions (template_id, version_number, name, content, created_at)
                VALUES (?, ?, 'Базовый', '{"stages":[],"transitions":[]}', CURRENT_TIMESTAMP)""", BASE_TEMPLATE, number);
    }

    private void mail(String status, OffsetDateTime at, String error) {
        jdbc.update("""
                INSERT INTO mail_outbox (id, event_key, kind, profile_id, subject, body_text, body_html, status, attempts,
                                         next_attempt_at, expires_at, created_at, sent_at, last_error)
                VALUES (?, ?, 'DIGEST', ?, 'Тема', 'текст', '<p>текст</p>', ?, 1, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), UUID.randomUUID().toString(), LEADER, status, at, at.plusDays(2), at, "SENT".equals(status) ? at : null, error);
    }

    private UUID createInteraction(String title) throws Exception {
        String body = mockMvc.perform(post("/api/interactions").with(login(KAM)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + ORGANIZATION + "\",\"title\":\"" + title + "\",\"contactIds\":[]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(objectMapper.readTree(body).path("id").asText());
    }

    private void profile(UUID id, UserRole role, UUID team) {
        String name = switch (role) {
            case ADMIN -> "Администратор";
            case LEADER -> "Руководитель";
            case MANAGEMENT -> "Руководство";
            default -> "КАМ";
        };
        jdbc.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, login)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, ISSUER, id.toString(), name, role.name(), team, "w59-" + id.toString().substring(32));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, UUID profile, String body) {
        return builder.with(login(profile)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestPostProcessor login(UUID profile) {
        String role = jdbc.queryForObject("SELECT role FROM crm_user_profiles WHERE id = ?", String.class, profile);
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profile.toString())
                .claim("realm_access", Map.of("roles", List.of(UserRole.valueOf(role).keycloakRole()))));
    }
}
