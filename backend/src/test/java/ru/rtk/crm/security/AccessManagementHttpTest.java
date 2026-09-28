package ru.rtk.crm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:access-management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-access-management-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret",
        "app.reports.max-rows=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
@ExtendWith(OutputCaptureExtension.class)
class AccessManagementHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID TEAM_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID LEADER_A = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID USER_A = UUID.fromString("50000000-0000-0000-0000-000000000003");
    private static final UUID PENDING = UUID.fromString("50000000-0000-0000-0000-000000000004");
    private static final UUID ORGANIZATION_A = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_B = UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final String ENROLMENT_KEY = randomKey();
    private static final String ENROLMENT_FINGERPRINT_KEY = randomKey();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void enrolmentModule(DynamicPropertyRegistry registry) {
        registry.add("app.enrolment.enabled", () -> "true");
        registry.add("app.enrolment.active-key-version", () -> "v1");
        registry.add("app.enrolment.keys.v1", () -> ENROLMENT_KEY);
        registry.add("app.enrolment.fingerprint-key", () -> ENROLMENT_FINGERPRINT_KEY);
        registry.add("app.sources.settings-key", AccessManagementHttpTest::randomKey);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        createSchema();
        jdbcTemplate.update("DELETE FROM audit_events");
        jdbcTemplate.update("DELETE FROM source_settings");
        jdbcTemplate.update("DELETE FROM crm_profile_events");
        jdbcTemplate.update("DELETE FROM organization_assignment_events");
        jdbcTemplate.update("DELETE FROM organization_team_events");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM command_idempotency_records");
        jdbcTemplate.update("DELETE FROM organizations");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        jdbcTemplate.update("DELETE FROM teams");
        jdbcTemplate.update("INSERT INTO teams (id, name) VALUES (?, ?), (?, ?)", TEAM_A, "Команда А", TEAM_B, "Команда Б");
        insertProfile(ADMIN, "Администратор", "ADMIN", null, true, false);
        insertProfile(LEADER_A, "Руководитель А", "LEADER", TEAM_A, true, false);
        insertProfile(USER_A, "КАМ А", "USER", TEAM_A, true, false);
        insertProfile(PENDING, "Новый сотрудник", "USER", null, false, true);
        insertOrganization(ORGANIZATION_A, "Университет А", TEAM_A, USER_A);
        insertOrganization(ORGANIZATION_B, "Университет Б", TEAM_B, null);
    }

    @Test
    void paidOrderUploadIsForbiddenWithoutOperatorFlagAndMeReportsTheFlag() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "orders.json", "application/json", "[null]".getBytes(StandardCharsets.UTF_8)
        );
        for (UUID profile : new UUID[] {ADMIN, USER_A}) {
            mockMvc.perform(multipart("/api/enrolment/paid-orders")
                            .file(file)
                            .with(login(profile))
                            .with(csrf())
                            .header("Idempotency-Key", "orders-" + profile))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value(containsString("оператору зачисления")))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
        mockMvc.perform(get("/api/me").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolmentOperator").value(false));

        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", USER_A)
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "grant-operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"enrolmentOperator\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolmentOperator").value(true));

        mockMvc.perform(get("/api/me").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolmentOperator").value(true));
    }

    @Test
    void enrolmentSectionAnswersForbiddenWithoutFlagBeforeLookingUpObjectsAndServesTheOperator() throws Exception {
        UUID unknown = UUID.randomUUID();
        for (UUID profile : new UUID[] {ADMIN, LEADER_A, USER_A}) {
            mockMvc.perform(get("/api/enrolment/streams").with(login(profile)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mockMvc.perform(get("/api/enrolment/learners/{id}", unknown).with(login(profile)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/enrolment/learners/search")
                            .with(login(profile))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"LAST_NAME\",\"value\":\"Тестова\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/enrolment/learners/{id}", unknown)
                            .with(login(profile))
                            .with(csrf())
                            .header("Idempotency-Key", "edit-" + profile)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0,\"fields\":{\"APARTMENT\":\"1\"}}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/enrolment/streams/{id}/lms-roster", unknown)
                            .with(login(profile))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"ALL\",\"incomplete\":\"INCLUDE\"}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE category = 'LEARNER'", Integer.class)).isZero();

        jdbcTemplate.update("UPDATE crm_user_profiles SET enrolment_operator = TRUE WHERE id = ?", USER_A);

        mockMvc.perform(get("/api/enrolment/streams").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streams").isEmpty())
                .andExpect(jsonPath("$.counters.profilesLimit").value(100000));
        mockMvc.perform(get("/api/enrolment/learners/{id}", unknown).with(login(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Слушатель не найден"));
        mockMvc.perform(get("/api/enrolment/streams/{id}/learners", unknown).with(login(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Поток не найден"));
        mockMvc.perform(get("/api/enrolment/learners/{id}", "not-an-id").with(login(USER_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.id").exists());
        mockMvc.perform(post("/api/enrolment/learners/search")
                        .with(login(USER_A))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SNILS\",\"value\":\"112-233-445 95\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(patch("/api/enrolment/learners/{id}", unknown)
                        .with(login(USER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "edit-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"fields\":{\"BIRTH_DATE\":\"31.02.2001\",\"SNILS\":\"1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.BIRTH_DATE").value("Дата должна быть в формате ГГГГ-ММ-ДД"));
        mockMvc.perform(post("/api/enrolment/roster-exports/{id}/transferred", unknown)
                        .with(login(USER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "mark-unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Выгрузка для LMS не найдена"));
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events WHERE category = 'LEARNER'", String.class))
                .containsExactly("LEARNER_SEARCHED");
    }

    @Test
    void leaderCreatesContactInOwnTeamAndForeignOrganizationIsHidden() throws Exception {
        mockMvc.perform(post("/api/organizations/{id}/contacts", ORGANIZATION_A)
                        .with(login(LEADER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "leader-contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ирина Куратор\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(LEADER_A.toString()));
        mockMvc.perform(post("/api/organizations/{id}/contacts", ORGANIZATION_B)
                        .with(login(LEADER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "leader-foreign-contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Чужой контакт\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void administratorTeamChangeAppliesToTheNextRequestOfTheChangedProfile() throws Exception {
        mockMvc.perform(get("/api/organizations/{id}", ORGANIZATION_A).with(login(USER_A)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", USER_A)
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "move-user-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"teamId\":\"" + TEAM_B + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamName").value("Команда Б"))
                .andExpect(jsonPath("$.accessRevision").value(1));

        mockMvc.perform(get("/api/me").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamName").value("Команда Б"))
                .andExpect(jsonPath("$.accessRevision").value(1));
        mockMvc.perform(get("/api/organizations/{id}", ORGANIZATION_A).with(login(USER_A)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizations").with(login(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", ADMIN)
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "self-role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").exists());
    }

    @Test
    void unknownTeamIsReportedInRussian() throws Exception {
        mockMvc.perform(patch("/api/admin/teams/{id}", UUID.randomUUID())
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "unknown-team")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Команда Икс\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Команда не найдена"));
    }

    @Test
    void pendingProfileHasNoAccessUntilActivation() throws Exception {
        mockMvc.perform(get("/api/me").with(login(PENDING)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CRM_PROFILE_PENDING"))
                .andExpect(jsonPath("$.message").value("Профиль CRM ожидает активации администратором"));
        mockMvc.perform(get("/api/organizations").with(login(PENDING)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CRM_PROFILE_PENDING"));
        mockMvc.perform(get("/api/admin/crm-profiles").param("pending", "true").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(PENDING.toString()));
    }

    @Test
    void journalCombinesTransfersAssignmentsAndSyncRunsFiltersThemAndRecordsItsExport() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        String today = LocalDate.now(ZoneId.of("Europe/Moscow")).toString();
        jdbcTemplate.update("""
                INSERT INTO organization_team_events (id, organization_id, previous_team_id, team_id, actor_profile_id,
                    actor_display_name, request_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'Администратор', 'transfer-request', ?)
                """, UUID.randomUUID(), ORGANIZATION_A, TEAM_A, TEAM_B, ADMIN, now);
        jdbcTemplate.update("""
                INSERT INTO organization_assignment_events (id, organization_id, command_id, previous_owner_manager_id,
                    previous_owner_manager_display_name, owner_manager_id, new_owner_manager_display_name, actor_profile_id,
                    actor_display_name, request_id, version, occurred_at)
                VALUES (?, ?, ?, NULL, NULL, ?, 'КАМ А', ?, 'Руководитель А', 'assignment-request', 1, ?)
                """, UUID.randomUUID(), ORGANIZATION_A, UUID.randomUUID(), USER_A, LEADER_A, now.minusMinutes(1));
        jdbcTemplate.update("""
                INSERT INTO sync_runs (id, source, status, started_by, fetched_count, created_count, updated_count,
                    needs_mapping_count, created_at)
                VALUES (?, 'MOODLE', 'SUCCEEDED', ?, 4, 1, 2, 1, ?)
                """, UUID.randomUUID(), ADMIN, now.minusMinutes(2));

        mockMvc.perform(get("/api/admin/audit-events")
                        .param("from", today).param("to", today).param("object", "университет а")
                        .with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].action").value("ORGANIZATION_TEAM_CHANGED"))
                .andExpect(jsonPath("$.items[0].details").value("команда: Команда А → Команда Б"))
                .andExpect(jsonPath("$.items[0].requestId").value("transfer-request"))
                .andExpect(jsonPath("$.items[1].action").value("KAM_ASSIGNED"))
                .andExpect(jsonPath("$.items[1].details").value("КАМ: не назначен → КАМ А"));
        mockMvc.perform(get("/api/admin/audit-events").param("actor", "руководитель").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].actorDisplayName").value("Руководитель А"));
        mockMvc.perform(get("/api/admin/audit-events").param("category", "SYNC").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].objectName").value("Moodle"))
                .andExpect(jsonPath("$.items[0].actorDisplayName").value("Администратор"));
        mockMvc.perform(get("/api/admin/audit-events")
                        .param("to", LocalDate.now(ZoneId.of("Europe/Moscow")).minusDays(1).toString())
                        .with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/admin/audit-events").param("from", today).param("to", "2020-01-01").with(login(ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").exists());

        String csv = mockMvc.perform(get("/api/admin/audit-events/export")
                        .param("format", "CSV").param("object", "Университет А")
                        .with(login(ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Вуз перенесён в другую команду").contains("Назначен ответственный КАМ").doesNotContain("Moodle");
        mockMvc.perform(get("/api/admin/audit-events").param("category", "DOWNLOAD").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("JOURNAL_EXPORTED"))
                .andExpect(jsonPath("$.items[0].details").value("формат CSV, строк: 2"));
        mockMvc.perform(get("/api/admin/audit-events/export").param("format", "XLSX").with(login(ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_ROW_LIMIT"))
                .andExpect(jsonPath("$.message").value("В выгрузку журнала попадает больше 3 строк; сузьте период или фильтры"));
        mockMvc.perform(get("/api/admin/audit-events").param("category", "DOWNLOAD").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/admin/audit-events/export").with(login(USER_A)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sourceSettingsAreAdminOnlyAndNeverExposeTokens() throws Exception {
        String token = "http-secret-moodle-token";
        String body = """
                {"moodleBaseUrl":"https://lms.example.test","moodleToken":"%s","moodleCourseIds":[2,3],
                 "moodleStudentRoles":["student"],"moodleTeacherRoles":["teacher"],"websiteBaseUrl":null,"syncCron":null}
                """.formatted(token);
        mockMvc.perform(get("/api/admin/source-settings").with(login(USER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/source-settings").with(login(LEADER_A)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/source-settings").with(login(USER_A)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/sources/MOODLE/check").with(login(USER_A)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_settings", Integer.class)).isZero();

        mockMvc.perform(put("/api/admin/source-settings").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("https://lms", "http://lms")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.moodleBaseUrl").exists());
        String saved = mockMvc.perform(put("/api/admin/source-settings").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moodleToken.origin").value("SCREEN"))
                .andExpect(jsonPath("$.moodleCourseIds[1]").value(3))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String read = mockMvc.perform(get("/api/admin/source-settings").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String journal = mockMvc.perform(get("/api/admin/audit-events").param("category", "SYNC").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("SOURCE_SETTINGS_CHANGED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(saved + read + journal).contains("токен Moodle заменён").doesNotContain(token);
        mockMvc.perform(delete("/api/admin/source-settings").with(login(ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_settings", Integer.class)).isZero();
    }

    private RequestPostProcessor login(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject(profileId.toString()));
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId, boolean active, boolean pending) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, pending_activation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ISSUER, id.toString(), displayName, role, teamId, active, pending);
    }

    private void insertOrganization(UUID id, String name, UUID teamId, UUID ownerManagerId) {
        jdbcTemplate.update("""
                INSERT INTO organizations (id, name, type, team_id, owner_manager_id, version, updated_at)
                VALUES (?, ?, 'UNIVERSITY', ?, ?, 0, ?)
                """, id, name, teamId, ownerManagerId, OffsetDateTime.parse("2026-09-24T09:00:00+00:00"));
    }

    @Test
    void pendingEmployeeNotifiesAdministratorOnceAndAdministratorFindsTheProfile() throws Exception {
        String first = mockMvc.perform(post("/api/me/activation-request").with(login(PENDING)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedAt").exists())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/me/activation-request").with(login(PENDING)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json(first));
        mockMvc.perform(post("/api/me/activation-request").with(login(USER_A)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.profile").exists());

        mockMvc.perform(get("/api/admin/crm-profiles").param("q", "новый").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pendingTotal").value(1))
                .andExpect(jsonPath("$.items[0].id").value(PENDING.toString()))
                .andExpect(jsonPath("$.items[0].activationRequestedAt").exists());
        mockMvc.perform(get("/api/admin/audit-events").param("category", "PROFILE").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].action").value("ACTIVATION_REQUESTED"))
                .andExpect(jsonPath("$.items[0].actorDisplayName").value("Новый сотрудник"));
    }

    @Test
    void securityFunctionsAreAvailableOnlyToAdministrator() throws Exception {
        mockMvc.perform(get("/api/admin/audit-events").with(login(LEADER_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/admin/personal-data/search")
                        .with(login(LEADER_A))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ирина Куратор\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/retention").with(login(USER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/retention").with(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportFilesDays").value(7))
                .andExpect(jsonPath("$.auditEventsDays").value(1095));
        mockMvc.perform(post("/api/admin/crm-profiles/{id}/account-sync", USER_A).with(login(LEADER_A)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void blockingWithoutKeycloakConnectionKeepsCrmBlockAndMarksAccountForSynchronization() throws Exception {
        mockMvc.perform(patch("/api/admin/crm-profiles/{id}", USER_A)
                        .with(login(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", "block-user-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.accountSyncRequired").value(true))
                .andExpect(jsonPath("$.accountSyncError").value(containsString("Связь с Keycloak не настроена")));
        mockMvc.perform(get("/api/me").with(login(USER_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/crm-profiles/{id}/account-sync", USER_A).with(login(ADMIN)).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SYNC_FAILED"));
    }

    @Test
    void clientErrorsAreLoggedWithRequestIdOperationAndReasonWithoutRequestData(CapturedOutput output) throws Exception {
        String requestId = mockMvc.perform(post("/api/organizations/{id}/contacts", ORGANIZATION_B)
                        .with(login(LEADER_A))
                        .with(csrf())
                        .header("Idempotency-Key", "hidden-contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Секретный Контакт\"}"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getHeader("X-Request-Id");

        assertThat(output.getOut())
                .contains("API error status=404 code=NOT_FOUND requestId=" + requestId)
                .contains("user=" + LEADER_A)
                .contains("operation=POST /api/organizations/" + ORGANIZATION_B + "/contacts")
                .contains("reason=Вуз не найден или недоступен")
                .doesNotContain("Секретный Контакт");
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id UUID PRIMARY KEY,
                    name VARCHAR(160) NOT NULL UNIQUE,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, archived BOOLEAN DEFAULT FALSE NOT NULL, default_workflow_template_id UUID
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_user_profiles (partner_organization_id UUID, partner_contact_id UUID, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY,
                    login VARCHAR(200),
                    idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    activation_requested_at TIMESTAMP WITH TIME ZONE,
                    anonymized_at TIMESTAMP WITH TIME ZONE,
                    issuer VARCHAR(512) NOT NULL,
                    subject VARCHAR(512) NOT NULL,
                    display_name VARCHAR(200) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    team_id UUID,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
                    access_revision INTEGER NOT NULL DEFAULT 0,
                    version INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (issuer, subject)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organizations (
                    id UUID PRIMARY KEY,
                    name VARCHAR(300) NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    team_id UUID NOT NULL,
                    owner_manager_id UUID,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL, city VARCHAR(200), website VARCHAR(300), inn VARCHAR(12)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_deputies (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, deputy_profile_id UUID NOT NULL,
                    deputy_display_name VARCHAR(200) NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL,
                    starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    command_id UUID NOT NULL, actor_profile_id UUID NOT NULL, actor_display_name VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, ended_at TIMESTAMP WITH TIME ZONE,
                    ended_by_profile_id UUID, ended_by_display_name VARCHAR(200)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS command_idempotency_records (
                    id UUID PRIMARY KEY,
                    actor_profile_id UUID NOT NULL,
                    operation VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    result_json VARCHAR(10000),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (actor_profile_id, operation, idempotency_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    decision_role VARCHAR(32), primary_contact BOOLEAN DEFAULT FALSE NOT NULL, inactive BOOLEAN DEFAULT FALSE NOT NULL, confirmed_at TIMESTAMP WITH TIME ZONE, confirmed_by UUID,
                    id UUID PRIMARY KEY,
                    personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    organization_id UUID NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    position VARCHAR(200),
                    email VARCHAR(320),
                    phone VARCHAR(50),
                    version INTEGER NOT NULL,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_assignment_events (
                    reason VARCHAR(32), handover_note VARCHAR(2000),
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    command_id UUID NOT NULL,
                    previous_owner_manager_id UUID,
                    previous_owner_manager_display_name VARCHAR(200),
                    owner_manager_id UUID,
                    new_owner_manager_display_name VARCHAR(200),
                    actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (command_id, organization_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crm_profile_events (previous_enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY,
                    profile_id UUID NOT NULL,
                    command_id UUID NOT NULL,
                    actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL,
                    previous_display_name VARCHAR(200) NOT NULL,
                    display_name VARCHAR(200) NOT NULL,
                    previous_role VARCHAR(16) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    previous_team_id UUID,
                    team_id UUID,
                    previous_active BOOLEAN NOT NULL,
                    active BOOLEAN NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    version INTEGER NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (command_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_team_events (
                    id UUID PRIMARY KEY,
                    organization_id UUID NOT NULL,
                    previous_team_id UUID NOT NULL,
                    team_id UUID NOT NULL,
                    actor_profile_id UUID NOT NULL,
                    actor_display_name VARCHAR(200) NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sync_runs (
                    id UUID PRIMARY KEY,
                    source VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    started_by UUID NOT NULL,
                    fetched_count INTEGER NOT NULL DEFAULT 0,
                    created_count INTEGER NOT NULL DEFAULT 0,
                    updated_count INTEGER NOT NULL DEFAULT 0,
                    needs_mapping_count INTEGER NOT NULL DEFAULT 0,
                    run_trigger VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS source_settings (
                    id SMALLINT PRIMARY KEY, moodle_base_url VARCHAR(500), moodle_token_encrypted VARCHAR(2000),
                    moodle_token_changed_at TIMESTAMP WITH TIME ZONE, moodle_course_ids VARCHAR(2000) NOT NULL,
                    moodle_student_roles VARCHAR(500) NOT NULL, moodle_teacher_roles VARCHAR(500) NOT NULL,
                    website_base_url VARCHAR(500), website_token_encrypted VARCHAR(2000),
                    website_token_changed_at TIMESTAMP WITH TIME ZONE, sync_cron VARCHAR(100), updated_by UUID NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY,
                    category VARCHAR(32) NOT NULL,
                    action VARCHAR(64) NOT NULL,
                    actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL,
                    object_type VARCHAR(32),
                    object_id UUID,
                    object_name VARCHAR(500),
                    details VARCHAR(2000),
                    request_id VARCHAR(64),
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS enrolment_streams (
                    id UUID PRIMARY KEY,
                    course_key VARCHAR(310) NOT NULL,
                    course_name VARCHAR(1333) NOT NULL,
                    stream_no INTEGER NOT NULL,
                    ends_on DATE,
                    version INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS learners (
                    id UUID PRIMARY KEY,
                    key_version VARCHAR(16),
                    fields TEXT,
                    email_hmac CHAR(64),
                    phone_hmac CHAR(64),
                    snils_hmac CHAR(64),
                    name_hmac CHAR(64),
                    last_name_hmac CHAR(64),
                    missing_fields VARCHAR(600) NOT NULL DEFAULT '',
                    personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    anonymized_at TIMESTAMP WITH TIME ZONE,
                    version INTEGER NOT NULL DEFAULT 0,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS learner_enrolments (
                    id UUID PRIMARY KEY,
                    learner_id UUID NOT NULL,
                    stream_id UUID NOT NULL,
                    source_record_id UUID,
                    lms_export_id UUID,
                    lms_exported_at TIMESTAMP WITH TIME ZONE,
                    lms_transferred_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS source_records (id UUID PRIMARY KEY, external_id VARCHAR(200), program_id UUID)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS programs (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS source_mappings (
                    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, kind VARCHAR(16) NOT NULL, external_key VARCHAR(310) NOT NULL,
                    program_id UUID
                )
                """);
    }
}
