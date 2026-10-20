package ru.rtk.crm.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.robustness.H2Schema;

// Отчётность по соглашениям через HTTP на схеме из настоящих миграций (V72 и прежние): справочники и настройки,
// обязательные атрибуты, согласование перечня (координатор, отметка КАМ, «молчание»), отчёт и совпадение экрана с
// файлами, права ролей и напоминания.
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.session.jdbc.initialize-schema=never",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-agreement-reporting-test",
        "app.reports.storage-root=${java.io.tmpdir}/rtk-crm-agreement-reporting-reports",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.api-rate-limit.per-minute=0",
        "app.api-rate-limit.anonymous-per-minute=0",
        "app.modules.partner.enabled=true"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("oidc")
class AgreementReportingHttpTest {
    private static final String DATABASE = "jdbc:h2:mem:agreement-reporting;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID KIND_PROGRAMS = UUID.fromString("7c2f0a10-0000-4000-8000-000000000010");
    private static final UUID KIND_PRACTICE = UUID.fromString("7c2f0a10-0000-4000-8000-000000000030");
    private static final UUID TEAM_A = UUID.fromString("a7000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("a7000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("a7100000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("a7100000-0000-0000-0000-000000000002");
    private static final UUID KAM_A = UUID.fromString("a7100000-0000-0000-0000-000000000003");
    private static final UUID KAM_B = UUID.fromString("a7100000-0000-0000-0000-000000000004");
    private static final UUID MANAGEMENT = UUID.fromString("a7100000-0000-0000-0000-000000000005");
    private static final UUID PARTNER = UUID.fromString("a7100000-0000-0000-0000-000000000006");
    private static final UUID WITH_COORDINATOR = UUID.fromString("a7200000-0000-0000-0000-000000000001");
    private static final UUID WITHOUT_COORDINATOR = UUID.fromString("a7200000-0000-0000-0000-000000000002");
    private static final UUID FOREIGN = UUID.fromString("a7200000-0000-0000-0000-000000000003");
    private static final UUID CONTACT = UUID.fromString("a7300000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private int keys;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL(DATABASE);
        dataSource.setUser("sa");
        JdbcTemplate schema = new JdbcTemplate(dataSource);
        Integer tables = schema.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'AGREEMENT_ACTIVITY_LISTS'", Integer.class);
        if (tables == null || tables == 0) {
            H2Schema.apply(schema);
        }
        registry.add("spring.datasource.url", () -> DATABASE);
    }

    @BeforeEach
    void seed() {
        for (String table : List.of("agreement_activity_list_events", "agreement_activity_list_items", "agreement_activity_lists",
                "agreement_rates", "agreement_activity_attachments", "agreement_activity_interactions", "agreement_activities",
                "agreement_status_events", "agreement_interactions", "agreements", "agreement_reporting_deadlines", "audit_events",
                "command_idempotency_records")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE agreement_activities SET executor_id = NULL");
        jdbc.update("DELETE FROM agreement_reference_items");
        jdbc.update("UPDATE agreement_activity_kinds SET required_attributes = NULL");
        jdbc.update("""
                UPDATE agreement_reporting_settings SET period_kind = 'CALENDAR', academic_year_start_month = 9, silence_days = 10,
                    repeat_silence_days = 5, reminder_days = 14, threshold_enabled = FALSE, threshold_percent = NULL,
                    threshold_base = NULL, threshold_base_label = NULL, report_columns = NULL WHERE id = 1
                """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id = ?", Integer.class, TEAM_A) == 0) {
            jdbc.update("INSERT INTO teams (id, name) VALUES (?, 'Команда отчётности А'), (?, 'Команда отчётности Б')", TEAM_A, TEAM_B);
            profile(ADMIN, UserRole.ADMIN, null);
            profile(LEADER_A, UserRole.LEADER, TEAM_A);
            profile(KAM_A, UserRole.USER, TEAM_A);
            profile(KAM_B, UserRole.USER, TEAM_B);
            profile(MANAGEMENT, UserRole.MANAGEMENT, null);
            organization(WITH_COORDINATOR, "Университет с координатором", TEAM_A, KAM_A);
            organization(WITHOUT_COORDINATOR, "Университет без координатора", TEAM_A, KAM_A);
            organization(FOREIGN, "Чужой университет", TEAM_B, KAM_B);
            jdbc.update("INSERT INTO contacts (id, organization_id, name, email, created_by) VALUES (?, ?, 'Координатор', 'c@example.test', ?)",
                    CONTACT, WITH_COORDINATOR, KAM_A);
            jdbc.update("""
                    INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, partner_organization_id, partner_contact_id)
                    VALUES (?, ?, ?, 'Координатор вуза', 'PARTNER', NULL, ?, ?)""", PARTNER, ISSUER, PARTNER.toString(), WITH_COORDINATOR,
                    CONTACT);
        }
    }

    @Test
    void onlyAdministratorMaintainsReferencesRatesAndSettingsAndEveryChangeIsJournaled() throws Exception {
        assertThat(call(HttpMethod.POST, "/api/admin/agreement-reporting/references", KAM_A, UserRole.USER,
                Map.of("category", "UNIT", "name", "ак. ч")).getStatus()).isEqualTo(403);
        UUID unit = id(created(ADMIN, UserRole.ADMIN, "/api/admin/agreement-reporting/references", Map.of("category", "UNIT", "name", "ак. ч")));
        assertThat(call(HttpMethod.POST, "/api/admin/agreement-reporting/references", ADMIN, UserRole.ADMIN,
                Map.of("category", "UNIT", "name", "АК. Ч")).getStatus()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/api/admin/agreement-reporting/references", ADMIN, UserRole.ADMIN,
                Map.of("category", "UNIT", "name", "люди", "baseAmount", 10)).getStatus()).isEqualTo(400);
        JsonNode references = json(call(HttpMethod.GET, "/api/agreement-reporting/references", MANAGEMENT, UserRole.MANAGEMENT, null));
        assertThat(references).hasSize(1);
        assertThat(call(HttpMethod.GET, "/api/agreement-reporting/settings", PARTNER, UserRole.PARTNER, null).getStatus()).isEqualTo(403);

        assertThat(call(HttpMethod.POST, "/api/admin/agreement-reporting/rates", LEADER_A, UserRole.LEADER,
                rate(KIND_PROGRAMS, unit, null, "2026-01-01", 100)).getStatus()).isEqualTo(403);
        JsonNode rate = created(ADMIN, UserRole.ADMIN, "/api/admin/agreement-reporting/rates", rate(KIND_PROGRAMS, unit, null, "2026-01-01", 100));
        assertThat(call(HttpMethod.POST, "/api/admin/agreement-reporting/rates", ADMIN, UserRole.ADMIN,
                rate(KIND_PROGRAMS, unit, null, "2026-01-01", 120)).getStatus()).isEqualTo(400);
        assertThat(call(HttpMethod.DELETE, "/api/admin/agreement-reporting/rates/" + rate.path("id").asText() + "?version=0", ADMIN,
                UserRole.ADMIN, null).getStatus()).isEqualTo(204);

        MockHttpServletResponse invalid = call(HttpMethod.PUT, "/api/admin/agreement-reporting/settings", ADMIN, UserRole.ADMIN,
                settings(true, null, null));
        assertThat(invalid.getStatus()).isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/api/admin/agreement-reporting/settings", KAM_A, UserRole.USER, settings(false, null, null))
                .getStatus()).isEqualTo(403);
        JsonNode saved = json(call(HttpMethod.PUT, "/api/admin/agreement-reporting/settings", ADMIN, UserRole.ADMIN,
                settings(true, 3, 500000)));
        assertThat(saved.path("settings").path("thresholdPercent").decimalValue()).isEqualByComparingTo("3");
        assertThat(saved.path("updatedByName").asText()).contains("ADMIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'AGREEMENT_REPORTING_CHANGED'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT details FROM audit_events WHERE action = 'AGREEMENT_REPORTING_CHANGED'", String.class))
                .anyMatch(details -> details.contains("свод против порога: выключен → включён"));
    }

    @Test
    void kindDecidesWhichActivityAttributesAreRequired() throws Exception {
        UUID unit = reference("UNIT", "ак. ч", null);
        UUID executor = reference("EXECUTOR", "ДЗО-1", null);
        setRequired(KIND_PROGRAMS, "EXECUTOR", "UNIT_QUANTITY", "DOCUMENT");
        UUID agreement = agreement(WITH_COORDINATOR, "Т-1");
        Map<String, Object> activity = activity(KIND_PROGRAMS, "Курс", unit, null, executor, 10, null, "2026-03-01", "PLANNED");
        activity.remove("executorId");
        MockHttpServletResponse missing = call(HttpMethod.POST, "/api/agreements/" + agreement + "/activities", KAM_A, UserRole.USER, activity);
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(json(missing).path("fieldErrors").toString()).contains("executorId");
        activity.put("executorId", executor);
        JsonNode created = created(KAM_A, UserRole.USER, "/api/agreements/" + agreement + "/activities", activity);
        assertThat(created.path("unit").asText()).isEqualTo("ак. ч");
        assertThat(created.path("executorName").asText()).isEqualTo("ДЗО-1");

        Map<String, Object> held = new java.util.HashMap<>(activity);
        held.put("version", 0);
        held.put("status", "DONE");
        MockHttpServletResponse noFact = call(HttpMethod.PATCH, "/api/agreement-activities/" + created.path("id").asText(), KAM_A,
                UserRole.USER, held);
        assertThat(json(noFact).path("fieldErrors").toString()).contains("actualVolume");
        held.put("actualVolume", 8);
        MockHttpServletResponse noDocument = call(HttpMethod.PATCH, "/api/agreement-activities/" + created.path("id").asText(), KAM_A,
                UserRole.USER, held);
        assertThat(json(noDocument).path("fieldErrors").toString()).contains("documentNote");
        held.put("documentNote", "Акт № 5 от 01.04.2026");
        held.put("status", "CONFIRMED");
        JsonNode confirmed = json(call(HttpMethod.PATCH, "/api/agreement-activities/" + created.path("id").asText(), KAM_A,
                UserRole.USER, held));
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT details FROM audit_events WHERE action = 'AGREEMENT_ACTIVITY_STATUS_CHANGED'", String.class))
                .isEqualTo("Запланировано → Подтверждено вузом");

        Map<String, Object> rejected = activity(KIND_PROGRAMS, "Отклонённое", null, null, null, null, null, "2026-03-01", "REJECTED");
        assertThat(call(HttpMethod.POST, "/api/agreements/" + agreement + "/activities", KAM_A, UserRole.USER, rejected).getStatus())
                .isEqualTo(201);
        assertThat(call(HttpMethod.PATCH, "/api/admin/agreement-activity-kinds/" + KIND_PROGRAMS, KAM_A, UserRole.USER,
                Map.of("version", 0, "name", "x", "archived", false, "requiredAttributes", List.of())).getStatus()).isEqualTo(403);
    }

    @Test
    void listIsApprovedByTheCoordinatorAfterReturnAndTheKamCannotDecideForHim() throws Exception {
        UUID agreement = agreement(WITH_COORDINATOR, "К-1");
        created(KAM_A, UserRole.USER, "/api/agreements/" + agreement + "/activities",
                activity(KIND_PROGRAMS, "Курс 2026", null, null, null, 5, null, "2026-04-01", "PLANNED"));
        created(KAM_A, UserRole.USER, "/api/agreements/" + agreement + "/activities",
                activity(KIND_PROGRAMS, "Курс 2025", null, null, null, 5, null, "2025-04-01", "PLANNED"));
        JsonNode list = created(KAM_A, UserRole.USER, "/api/organizations/" + WITH_COORDINATOR + "/activity-lists", Map.of("year", 2026));
        String id = list.path("summary").path("id").asText();
        assertThat(list.path("summary").path("status").asText()).isEqualTo("DRAFT");
        assertThat(list.path("items")).hasSize(1);
        assertThat(list.path("summary").path("title").asText()).isEqualTo("Перечень мероприятий на 2026 год");
        assertThat(list.path("coordinatorAvailable").asBoolean()).isTrue();

        assertThat(call(HttpMethod.GET, "/api/partner/activity-lists", PARTNER, UserRole.PARTNER, null).getContentAsString())
                .isEqualTo("[]");
        JsonNode sent = transition(id, KAM_A, 0, "SEND", null);
        assertThat(sent.path("summary").path("status").asText()).isEqualTo("REVIEW");
        assertThat(sent.path("summary").path("reviewDueOn").asText()).isEqualTo(today().plusDays(10).toString());
        assertThat(sent.path("actions")).isEmpty();
        MockHttpServletResponse kamMark = call(HttpMethod.POST, "/api/activity-lists/" + id + "/transitions", KAM_A, UserRole.USER,
                Map.of("version", 1, "action", "APPROVE"));
        assertThat(kamMark.getStatus()).isEqualTo(400);

        JsonNode partnerLists = json(call(HttpMethod.GET, "/api/partner/activity-lists", PARTNER, UserRole.PARTNER, null));
        assertThat(partnerLists).hasSize(1);
        assertThat(partnerLists.get(0).path("actions").toString()).contains("APPROVE", "RETURN");
        assertThat(call(HttpMethod.POST, "/api/partner/activity-lists/" + id + "/decision", PARTNER, UserRole.PARTNER,
                Map.of("version", 1, "decision", "RETURN")).getStatus()).isEqualTo(400);
        JsonNode returned = json(call(HttpMethod.POST, "/api/partner/activity-lists/" + id + "/decision", PARTNER, UserRole.PARTNER,
                Map.of("version", 1, "decision", "RETURN", "comment", "Добавьте стажировки")));
        assertThat(returned.path("summary").path("status").asText()).isEqualTo("RETURNED");

        JsonNode again = transition(id, KAM_A, 2, "SEND", "Добавили");
        assertThat(again.path("summary").path("reviewRound").asInt()).isEqualTo(2);
        assertThat(again.path("summary").path("reviewDueOn").asText()).isEqualTo(today().plusDays(5).toString());
        JsonNode approved = json(call(HttpMethod.POST, "/api/partner/activity-lists/" + id + "/decision", PARTNER, UserRole.PARTNER,
                Map.of("version", 3, "decision", "APPROVE")));
        assertThat(approved.path("summary").path("status").asText()).isEqualTo("APPROVED");
        JsonNode history = json(call(HttpMethod.GET, "/api/activity-lists/" + id, LEADER_A, UserRole.LEADER, null)).path("history");
        assertThat(history).hasSize(5);
        assertThat(history.get(0).path("channel").asText()).isEqualTo("COORDINATOR");
        assertThat(history.get(0).path("actorName").asText()).isEqualTo("Координатор вуза");
        assertThat(history.get(1).path("comment").asText()).contains("в кабинет вуза");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'AGREEMENT_ACTIVITY_LIST_CHANGED'", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void withoutCoordinatorTheKamMarksTheAnswerAndSilenceApprovesAfterTheDeadline() throws Exception {
        UUID agreement = agreement(WITHOUT_COORDINATOR, "Б-1");
        created(KAM_A, UserRole.USER, "/api/agreements/" + agreement + "/activities",
                activity(KIND_PRACTICE, "Практика", null, null, null, 3, null, "2026-05-01", "PLANNED"));
        String marked = created(KAM_A, UserRole.USER, "/api/organizations/" + WITHOUT_COORDINATOR + "/activity-lists",
                Map.of("year", 2026)).path("summary").path("id").asText();
        JsonNode sent = transition(marked, KAM_A, 0, "SEND", null);
        assertThat(sent.path("actions").toString()).contains("APPROVE", "RETURN");
        assertThat(sent.path("history").get(0).path("comment").asText()).contains("ответ отметит КАМ");
        MockHttpServletResponse future = call(HttpMethod.POST, "/api/activity-lists/" + marked + "/transitions", KAM_A, UserRole.USER,
                Map.of("version", 1, "action", "APPROVE", "respondedOn", today().plusDays(1).toString()));
        assertThat(future.getStatus()).isEqualTo(400);
        JsonNode approved = json(call(HttpMethod.POST, "/api/activity-lists/" + marked + "/transitions", KAM_A, UserRole.USER,
                Map.of("version", 1, "action", "APPROVE", "respondedOn", today().toString())));
        assertThat(approved.path("summary").path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("history").get(0).path("channel").asText()).isEqualTo("KAM");
        assertThat(approved.path("history").get(0).path("comment").asText()).startsWith("Отметка КАМ: ответ вуза от");

        String silent = created(KAM_A, UserRole.USER, "/api/organizations/" + WITHOUT_COORDINATOR + "/activity-lists",
                Map.of("year", 2026, "title", "Итоговый перечень")).path("summary").path("id").asText();
        transition(silent, KAM_A, 0, "SEND", null);
        LocalDate due = today().minusDays(1);
        jdbc.update("UPDATE agreement_activity_lists SET review_due_on = ? WHERE id = ?", due, UUID.fromString(silent));
        JsonNode settled = json(call(HttpMethod.GET, "/api/activity-lists/" + silent, KAM_A, UserRole.USER, null));
        assertThat(settled.path("summary").path("status").asText()).isEqualTo("APPROVED_SILENTLY");
        assertThat(settled.path("summary").path("decidedOn").asText()).isEqualTo(today().toString());
        // События одного мгновения идут в неопределённом порядке: ищем событие «молчания» по статусу, а не по месту.
        assertThat(settled.path("history").findParents("toStatus").stream()
                .filter(event -> event.path("toStatus").asText().equals("APPROVED_SILENTLY"))
                .map(event -> event.path("channel").asText())).containsExactly("SYSTEM");
        assertThat(settled.path("actions")).isEmpty();

        // Без срока в настройках «молчания» нет: перечень ждёт ответа сколько угодно.
        jdbc.update("UPDATE agreement_reporting_settings SET silence_days = NULL, repeat_silence_days = NULL");
        String waiting = created(KAM_A, UserRole.USER, "/api/organizations/" + WITHOUT_COORDINATOR + "/activity-lists",
                Map.of("year", 2026)).path("summary").path("id").asText();
        JsonNode open = transition(waiting, KAM_A, 0, "SEND", null);
        assertThat(open.path("summary").path("reviewDueOn").isNull()).isTrue();
        assertThat(open.path("history").get(0).path("comment").asText()).contains("срок ответа не задан");
    }

    @Test
    void reportValuesActivitiesByTheRateInForceAndComparesExecutorsWithTheThreshold() throws Exception {
        UUID hours = reference("UNIT", "ак. ч", null);
        UUID higher = reference("EDUCATION_LEVEL", "ВО", null);
        UUID first = reference("EXECUTOR", "ДЗО-1", 100000);
        UUID second = reference("EXECUTOR", "ДЗО-2", 1000000);
        created(ADMIN, UserRole.ADMIN, "/api/admin/agreement-reporting/rates", rate(KIND_PROGRAMS, hours, null, "2026-01-01", 100));
        created(ADMIN, UserRole.ADMIN, "/api/admin/agreement-reporting/rates", rate(KIND_PROGRAMS, hours, higher, "2026-06-01", 150));
        call(HttpMethod.PUT, "/api/admin/agreement-reporting/settings", ADMIN, UserRole.ADMIN, settings(true, 3, null));
        UUID agreement = agreement(WITH_COORDINATOR, "О-1");
        Map<String, Object> early = activity(KIND_PROGRAMS, "До новой ставки", hours, higher, first, 10, 10, "2026-03-10", "DONE");
        Map<String, Object> late = activity(KIND_PROGRAMS, "После новой ставки", hours, higher, first, 20, 20, "2026-09-10", "CONFIRMED");
        Map<String, Object> noUnit = activity(KIND_PRACTICE, "Без единицы", null, null, second, 4, null, "2026-09-10", "PLANNED");
        noUnit.put("participants", 12);
        noUnit.put("cost", 2500.5);
        for (Map<String, Object> activity : List.of(early, late, noUnit)) {
            created(KAM_A, UserRole.USER, "/api/agreements/" + agreement + "/activities", activity);
        }
        created(KAM_B, UserRole.USER, "/api/agreements/" + agreement(FOREIGN, "Ч-1", KAM_B) + "/activities",
                activity(KIND_PROGRAMS, "Чужое", hours, null, null, 1, 1, "2026-03-10", "DONE"));

        JsonNode report = json(call(HttpMethod.GET, "/api/reports/agreement-reporting?from=2026-01-01&to=2026-12-31", KAM_A, UserRole.USER,
                null));
        assertThat(report.path("rows")).hasSize(3);
        int title = column(report, "TITLE");
        int actual = column(report, "ACTUAL_VALUATION");
        int rateColumn = column(report, "RATE");
        assertThat(row(report, title, "До новой ставки").get(rateColumn).decimalValue()).isEqualByComparingTo("100");
        assertThat(row(report, title, "До новой ставки").get(actual).decimalValue()).isEqualByComparingTo("1000");
        assertThat(row(report, title, "После новой ставки").get(actual).decimalValue()).isEqualByComparingTo("3000");
        assertThat(row(report, title, "Без единицы").get(rateColumn).isNull()).isTrue();
        assertThat(report.path("totals").path("actualValuation").decimalValue()).isEqualByComparingTo("4000");
        assertThat(report.path("totals").path("withoutRate").asInt()).isEqualTo(1);
        assertThat(report.path("totals").path("participants").asInt()).isEqualTo(12);
        assertThat(report.path("totals").path("cost").decimalValue()).isEqualByComparingTo("2500.50");
        assertThat(report.path("byExecutor").get(0).path("name").asText()).isEqualTo("ДЗО-1");
        assertThat(report.path("byLevel").get(1).path("name").asText()).isEqualTo("Не указан");

        JsonNode threshold = report.path("threshold");
        assertThat(threshold.path("enabled").asBoolean()).isTrue();
        JsonNode dzo1 = threshold.path("rows").get(0);
        assertThat(dzo1.path("required").decimalValue()).isEqualByComparingTo("3000");
        assertThat(dzo1.path("actualMet").asBoolean()).isTrue();
        JsonNode dzo2 = threshold.path("rows").get(1);
        assertThat(dzo2.path("required").decimalValue()).isEqualByComparingTo("30000");
        assertThat(dzo2.path("actualMet").asBoolean()).isFalse();
        assertThat(threshold.path("total").path("base").decimalValue()).isEqualByComparingTo("1100000");

        JsonNode foreign = json(call(HttpMethod.GET, "/api/reports/agreement-reporting?from=2026-01-01&to=2026-12-31", KAM_B, UserRole.USER,
                null));
        assertThat(foreign.path("rows")).hasSize(1);
        assertThat(json(call(HttpMethod.GET, "/api/reports/agreement-reporting?from=2026-01-01&to=2026-12-31", MANAGEMENT,
                UserRole.MANAGEMENT, null)).path("rows")).hasSize(4);
        assertThat(call(HttpMethod.GET, "/api/reports/agreement-reporting", ADMIN, UserRole.ADMIN, null).getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/reports/agreement-reporting", PARTNER, UserRole.PARTNER, null).getStatus()).isEqualTo(403);
    }

    @Test
    void screenAndAllFilesShowTheSameRowsInTheAdministratorTemplate() throws Exception {
        UUID hours = reference("UNIT", "ак. ч", null);
        created(ADMIN, UserRole.ADMIN, "/api/admin/agreement-reporting/rates", rate(KIND_PROGRAMS, hours, null, "2026-01-01", 1234.5));
        UUID agreement = agreement(WITH_COORDINATOR, "Ф-1");
        created(KAM_A, UserRole.USER, "/api/agreements/" + agreement + "/activities",
                activity(KIND_PROGRAMS, "=Формула", hours, null, null, 2, 2, "2026-03-10", "DONE"));
        created(KAM_A, UserRole.USER, "/api/agreements/" + agreement + "/activities",
                activity(KIND_PROGRAMS, "Второе", hours, null, null, 1, null, "2026-04-10", "PLANNED"));
        JsonNode template = json(call(HttpMethod.PUT, "/api/admin/agreement-reporting/template", ADMIN, UserRole.ADMIN, Map.of("columns", List.of(
                Map.of("code", "ACTUAL_VALUATION", "title", "Стоимость мероприятия, тыс. руб."),
                Map.of("code", "TITLE", "title", ""),
                Map.of("code", "ACTUAL_VOLUME")))));
        assertThat(template.path("defaultColumns").asBoolean()).isFalse();
        assertThat(call(HttpMethod.PUT, "/api/admin/agreement-reporting/template", ADMIN, UserRole.ADMIN,
                Map.of("columns", List.of(Map.of("code", "TITLE"), Map.of("code", "TITLE")))).getStatus()).isEqualTo(400);

        String query = "?from=2026-01-01&to=2026-12-31&organizationId=" + WITH_COORDINATOR;
        JsonNode screen = json(call(HttpMethod.GET, "/api/reports/agreement-reporting" + query, KAM_A, UserRole.USER, null));
        assertThat(screen.path("columns").get(0).path("title").asText()).isEqualTo("Стоимость мероприятия, тыс. руб.");
        assertThat(screen.path("columns").get(1).path("title").asText()).isEqualTo("Мероприятие");
        assertThat(screen.path("rows").get(0).get(0).decimalValue()).isEqualByComparingTo("2469.00");

        MockHttpServletResponse jsonFile = call(HttpMethod.GET, "/api/reports/agreement-reporting/file" + query + "&format=JSON", KAM_A,
                UserRole.USER, null);
        JsonNode fromFile = json(jsonFile);
        assertThat(fromFile.path("rows")).isEqualTo(screen.path("rows"));
        assertThat(fromFile.path("columns")).isEqualTo(screen.path("columns"));
        assertThat(fromFile.path("threshold")).isEqualTo(screen.path("threshold"));

        byte[] xlsx = call(HttpMethod.GET, "/api/reports/agreement-reporting/file" + query + "&format=XLSX", KAM_A, UserRole.USER, null)
                .getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheet("Мероприятия");
            int header = screen.path("notes").size() + 2;
            Row titles = sheet.getRow(header);
            for (int column = 0; column < screen.path("columns").size(); column++) {
                assertThat(titles.getCell(column).getStringCellValue()).isEqualTo(screen.path("columns").get(column).path("title").asText());
            }
            for (int index = 0; index < screen.path("rows").size(); index++) {
                Row cells = sheet.getRow(header + 1 + index);
                JsonNode values = screen.path("rows").get(index);
                for (int column = 0; column < values.size(); column++) {
                    Cell cell = cells.getCell(column);
                    JsonNode value = values.get(column);
                    if (value.isNull()) {
                        assertThat(cell == null || cell.getCellType() == CellType.BLANK).isTrue();
                    } else if (value.isNumber()) {
                        assertThat(cell.getNumericCellValue()).isEqualTo(value.doubleValue());
                    } else {
                        assertThat(cell.getStringCellValue()).isEqualTo(value.asText());
                    }
                }
            }
            assertThat(sheet.getRow(header + 1).getCell(1).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(workbook.getSheet("По видам")).isNotNull();
        }

        byte[] pdf = call(HttpMethod.GET, "/api/reports/agreement-reporting/file" + query + "&format=PDF", KAM_A, UserRole.USER, null)
                .getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document).replace(' ', ' ');
            assertThat(text).contains("Отчёт по соглашениям", "Второе", "2 469,00");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'REPORT_DOWNLOADED'", Integer.class)).isEqualTo(3);

        call(HttpMethod.DELETE, "/api/admin/agreement-reporting/template", ADMIN, UserRole.ADMIN, null);
        assertThat(json(call(HttpMethod.GET, "/api/reports/agreement-reporting" + query, KAM_A, UserRole.USER, null)).path("columns").size())
                .isEqualTo(AgreementReportService.Field.values().length);
    }

    @Test
    void rolesSeeAndChangeListsOnlyWithinTheirScope() throws Exception {
        agreement(WITH_COORDINATOR, "П-1");
        JsonNode view = json(call(HttpMethod.GET, "/api/organizations/" + WITH_COORDINATOR + "/activity-lists", MANAGEMENT,
                UserRole.MANAGEMENT, null));
        assertThat(view.path("coordinatorAvailable").asBoolean()).isTrue();
        assertThat(view.path("currentPeriod").path("label").asText()).endsWith(" год");
        assertThat(call(HttpMethod.POST, "/api/organizations/" + WITH_COORDINATOR + "/activity-lists", MANAGEMENT, UserRole.MANAGEMENT,
                Map.of("year", 2026)).getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/organizations/" + WITH_COORDINATOR + "/activity-lists", KAM_B, UserRole.USER, null)
                .getStatus()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/organizations/" + WITH_COORDINATOR + "/activity-lists", ADMIN, UserRole.ADMIN, null)
                .getStatus()).isEqualTo(404);
        String leaderList = created(LEADER_A, UserRole.LEADER, "/api/organizations/" + WITH_COORDINATOR + "/activity-lists",
                Map.of("year", 2026)).path("summary").path("id").asText();
        assertThat(call(HttpMethod.GET, "/api/activity-lists/" + leaderList, KAM_B, UserRole.USER, null).getStatus()).isEqualTo(404);
        JsonNode managementView = json(call(HttpMethod.GET, "/api/activity-lists/" + leaderList, MANAGEMENT, UserRole.MANAGEMENT, null));
        assertThat(managementView.path("actions")).isEmpty();
        assertThat(call(HttpMethod.POST, "/api/activity-lists/" + leaderList + "/transitions", MANAGEMENT, UserRole.MANAGEMENT,
                Map.of("version", 0, "action", "SEND")).getStatus()).isEqualTo(403);

        // Пустой перечень отправить нельзя: у вуза должно быть мероприятие плана.
        created(KAM_B, UserRole.USER, "/api/agreements/" + agreement(FOREIGN, "Ч-1", KAM_B) + "/activities",
                activity(KIND_PRACTICE, "Практика", null, null, null, 3, null, "2026-05-01", "PLANNED"));
        String foreignList = created(KAM_B, UserRole.USER, "/api/organizations/" + FOREIGN + "/activity-lists", Map.of("year", 2026))
                .path("summary").path("id").asText();
        transition(foreignList, KAM_B, 0, "SEND", null);
        assertThat(call(HttpMethod.POST, "/api/partner/activity-lists/" + foreignList + "/decision", PARTNER, UserRole.PARTNER,
                Map.of("version", 1, "decision", "APPROVE")).getStatus()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/partner/activity-lists", KAM_A, UserRole.USER, null).getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.PATCH, "/api/activity-lists/" + foreignList, KAM_B, UserRole.USER,
                Map.of("version", 1, "title", "Новое")).getStatus()).isEqualTo(400);
    }

    @Test
    void emptyListCannotBeSentToTheUniversity() throws Exception {
        String empty = created(KAM_A, UserRole.USER, "/api/organizations/" + WITH_COORDINATOR + "/activity-lists", Map.of("year", 2026))
                .path("summary").path("id").asText();
        MockHttpServletResponse response = call(HttpMethod.POST, "/api/activity-lists/" + empty + "/transitions", KAM_A, UserRole.USER,
                Map.of("version", 0, "action", "SEND"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains(ActivityListService.EMPTY_LIST);
        JsonNode still = json(call(HttpMethod.GET, "/api/activity-lists/" + empty, KAM_A, UserRole.USER, null));
        assertThat(still.path("summary").path("status").asText()).isEqualTo("DRAFT");
        assertThat(call(HttpMethod.GET, "/api/partner/activity-lists", PARTNER, UserRole.PARTNER, null).getContentAsString())
                .doesNotContain(empty);
    }

    @Test
    void deadlinesAndListsAwaitingTheUniversityReachTheBellAndControl() throws Exception {
        LocalDate soon = today().plusDays(3);
        JsonNode saved = json(call(HttpMethod.PUT, "/api/admin/agreement-reporting/deadlines", ADMIN, UserRole.ADMIN, Map.of("deadlines", List.of(
                Map.of("name", "Перечень вузам", "dueOn", soon.minusYears(1).toString(), "deliverable", "перечень", "annual", true),
                Map.of("name", "Далёкий срок", "dueOn", today().plusDays(200).toString())))));
        assertThat(saved.path("deadlines")).hasSize(2);
        created(KAM_A, UserRole.USER, "/api/agreements/" + agreement(WITHOUT_COORDINATOR, "С-1") + "/activities",
                activity(KIND_PRACTICE, "Практика", null, null, null, 3, null, "2026-05-01", "PLANNED"));
        String list = created(KAM_A, UserRole.USER, "/api/organizations/" + WITHOUT_COORDINATOR + "/activity-lists", Map.of("year", 2026))
                .path("summary").path("id").asText();
        transition(list, KAM_A, 0, "SEND", null);

        JsonNode reminders = json(call(HttpMethod.GET, "/api/agreement-reporting/reminders", LEADER_A, UserRole.LEADER, null));
        assertThat(reminders.path("deadlines")).hasSize(1);
        assertThat(reminders.path("deadlines").get(0).path("dueOn").asText()).isEqualTo(soon.toString());
        assertThat(reminders.path("deadlines").get(0).path("daysLeft").asInt()).isEqualTo(3);
        assertThat(reminders.path("lists").get(0).path("status").asText()).isEqualTo("REVIEW");
        JsonNode bell = json(call(HttpMethod.GET, "/api/reminders", KAM_A, UserRole.USER, null));
        assertThat(bell.path("agreementReporting").path("deadlines").get(0).path("name").asText()).isEqualTo("Перечень вузам");
        assertThat(bell.path("agreementReporting").path("lists")).hasSize(1);
        assertThat(json(call(HttpMethod.GET, "/api/agreement-reporting/reminders", KAM_B, UserRole.USER, null)).path("lists")).isEmpty();
    }

    // ---------- помощники ----------

    private UUID reference(String category, String name, Integer base) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("category", category, "name", name));
        if (base != null) {
            body.put("baseAmount", base);
        }
        return id(created(ADMIN, UserRole.ADMIN, "/api/admin/agreement-reporting/references", body));
    }

    private void setRequired(UUID kind, String... attributes) throws Exception {
        JsonNode current = json(call(HttpMethod.GET, "/api/agreement-activity-kinds", KAM_A, UserRole.USER, null));
        for (JsonNode item : current) {
            if (item.path("id").asText().equals(kind.toString())) {
                JsonNode updated = json(call(HttpMethod.PATCH, "/api/admin/agreement-activity-kinds/" + kind, ADMIN, UserRole.ADMIN,
                        Map.of("version", item.path("version").asInt(), "name", item.path("name").asText(), "archived", false,
                                "requiredAttributes", List.of(attributes))));
                assertThat(updated.path("requiredAttributes")).hasSize(attributes.length);
            }
        }
    }

    private UUID agreement(UUID organization, String number) throws Exception {
        return agreement(organization, number, KAM_A);
    }

    private UUID agreement(UUID organization, String number, UUID kam) throws Exception {
        return id(created(kam, UserRole.USER, "/api/organizations/" + organization + "/agreements",
                Map.of("number", number, "status", "ACTIVE", "concludedOn", "2026-01-15", "validUntil", "2028-12-31")));
    }

    private static Map<String, Object> activity(UUID kind, String title, UUID unit, UUID level, UUID executor, Integer planned,
                                                Integer actual, String date, String status) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("kindId", kind);
        body.put("title", title);
        body.put("unitId", unit);
        body.put("educationLevelId", level);
        body.put("executorId", executor);
        body.put("plannedVolume", planned);
        body.put("actualVolume", actual);
        body.put("plannedStart", date);
        body.put("plannedEnd", date);
        body.put("status", status);
        return body;
    }

    private static Map<String, Object> rate(UUID kind, UUID unit, UUID level, String from, Number rate) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("kindId", kind);
        body.put("unitId", unit);
        body.put("educationLevelId", level);
        body.put("effectiveFrom", from);
        body.put("rate", rate);
        return body;
    }

    private static Map<String, Object> settings(boolean threshold, Integer percent, Integer base) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("periodKind", "CALENDAR");
        body.put("academicYearStartMonth", 9);
        body.put("silenceDays", 10);
        body.put("repeatSilenceDays", 5);
        body.put("reminderDays", 14);
        body.put("thresholdEnabled", threshold);
        body.put("thresholdPercent", percent);
        body.put("thresholdBase", base);
        body.put("thresholdBaseLabel", base == null ? null : "сэкономленные средства");
        return body;
    }

    private JsonNode transition(String list, UUID kam, int version, String action, String comment) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("version", version, "action", action));
        if (comment != null) {
            body.put("comment", comment);
        }
        MockHttpServletResponse response = call(HttpMethod.POST, "/api/activity-lists/" + list + "/transitions", kam, UserRole.USER, body);
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return json(response);
    }

    private static int column(JsonNode report, String code) {
        for (int index = 0; index < report.path("columns").size(); index++) {
            if (report.path("columns").get(index).path("code").asText().equals(code)) {
                return index;
            }
        }
        throw new AssertionError("Нет колонки " + code);
    }

    private static JsonNode row(JsonNode report, int column, String value) {
        List<JsonNode> found = new ArrayList<>();
        report.path("rows").forEach(row -> {
            if (row.get(column).asText().equals(value)) {
                found.add(row);
            }
        });
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private JsonNode created(UUID profile, UserRole role, String path, Object body) throws Exception {
        MockHttpServletResponse response = call(HttpMethod.POST, path, profile, role, body);
        assertThat(response.getStatus()).as(path + " " + response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
        return json(response);
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.path("id").asText());
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse call(HttpMethod method, String path, UUID profile, UserRole role, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path).with(login(profile, role)).with(csrf())
                .header("Idempotency-Key", "reporting-" + (++keys));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static OidcLoginRequestPostProcessor login(UUID profile, UserRole role) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profile.toString())
                .claim("realm_access", Map.of("roles", List.of(role.keycloakRole()))));
    }

    private void profile(UUID id, UserRole role, UUID team) {
        jdbc.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, login)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, ISSUER, id.toString(), role + " " + id.toString().substring(33), role.name(), team,
                "reporting-" + id.toString().substring(33));
    }

    private void organization(UUID id, String name, UUID team, UUID owner) {
        jdbc.update("INSERT INTO organizations (id, name, type, team_id, owner_manager_id) VALUES (?, ?, 'UNIVERSITY', ?, ?)",
                id, name, team, owner);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Moscow"));
    }
}
