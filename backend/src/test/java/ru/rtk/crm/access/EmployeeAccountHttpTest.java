package ru.rtk.crm.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:employee-accounts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.session.jdbc.initialize-schema=always",
        "app.attachments.storage-root=${java.io.tmpdir}/rtk-crm-employee-accounts-test",
        "app.oidc.issuer-uri=http://crm.test/idp/realms/rtk-crm",
        "app.oidc.public-base-url=http://crm.test",
        "app.oidc.internal-base-url=http://keycloak.test",
        "app.oidc.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
@ExtendWith(OutputCaptureExtension.class)
class EmployeeAccountHttpTest {
    private static final String ISSUER = "http://crm.test/idp/realms/rtk-crm";
    private static final UUID TEAM = UUID.fromString("42000000-0000-0000-0000-000000000001");
    private static final UUID ARCHIVED_TEAM = UUID.fromString("42000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final UUID KAM = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID LEADER = UUID.fromString("52000000-0000-0000-0000-000000000003");
    private static final UUID MANAGEMENT = UUID.fromString("52000000-0000-0000-0000-000000000004");
    private static final UUID PARTNER = UUID.fromString("52000000-0000-0000-0000-000000000005");
    private static final String NEW_EMPLOYEE = """
            {"displayName":"Петрова Анна Сергеевна","login":" Petrova ","email":"Petrova@Example.test","role":"USER","teamId":"%s"}
            """.formatted(TEAM);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KeycloakAccountClient keycloakAccountClient;

    @BeforeEach
    void setUp() {
        createSchema();
        for (String table : new String[] {
                "audit_events", "crm_profile_events", "command_idempotency_records", "spring_session", "crm_user_profiles", "teams"
        }) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("INSERT INTO teams (id, name, archived) VALUES (?, 'Команда', FALSE), (?, 'Старая', TRUE)",
                TEAM, ARCHIVED_TEAM);
        insertProfile(ADMIN, "Администратор", "ADMIN", null, "admin");
        insertProfile(KAM, "КАМ", "USER", TEAM, "kam");
        insertProfile(LEADER, "Руководитель", "LEADER", TEAM, "leader");
        insertProfile(MANAGEMENT, "Руководство", "MANAGEMENT", null, "management");
        insertProfile(PARTNER, "Представитель вуза", "PARTNER", null, "vuz-1");
    }

    @Test
    void administratorCreatesEmployeeWithOneTimePasswordThatIsStoredNowhere(CapturedOutput output) throws Exception {
        when(keycloakAccountClient.createEmployeeUser(
                eq("petrova"), eq("petrova@example.test"), eq("Анна Сергеевна"), eq("Петрова"), anyString()
        )).thenReturn("kc-petrova");

        String body = mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "create-1").content(NEW_EMPLOYEE))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.login").value("petrova"))
                .andExpect(jsonPath("$.profile.displayName").value("Петрова Анна Сергеевна"))
                .andExpect(jsonPath("$.profile.role").value("USER"))
                .andExpect(jsonPath("$.profile.teamName").value("Команда"))
                .andExpect(jsonPath("$.profile.active").value(true))
                .andExpect(jsonPath("$.profile.pendingActivation").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String password = objectMapper.readTree(body).path("temporaryPassword").asText();
        assertThat(password).hasSize(16).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*");
        verify(keycloakAccountClient).createEmployeeUser(
                eq("petrova"), eq("petrova@example.test"), eq("Анна Сергеевна"), eq("Петрова"), eq(password)
        );
        assertThat(jdbcTemplate.queryForMap(
                "SELECT issuer, subject, login, idp_enabled FROM crm_user_profiles WHERE login = 'petrova'"))
                .containsEntry("ISSUER", ISSUER)
                .containsEntry("SUBJECT", "kc-petrova")
                .containsEntry("IDP_ENABLED", true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT details FROM audit_events WHERE action = 'ACCOUNT_CREATED'", String.class))
                .contains("логин petrova");

        String replay = mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "create-1").content(NEW_EMPLOYEE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
                .andExpect(jsonPath("$.profile.login").value("petrova"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        verify(keycloakAccountClient, times(1)).createEmployeeUser(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(replay).doesNotContain(password);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_user_profiles WHERE login = 'petrova'", Integer.class))
                .isEqualTo(1);
        assertStoredNowhere(password, output);
    }

    @Test
    void invalidOrConflictingEmployeeLeavesNoProfile() throws Exception {
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "bad-team")
                        .content(NEW_EMPLOYEE.replace(TEAM.toString(), ARCHIVED_TEAM.toString())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "no-team")
                        .content("{\"displayName\":\"Без команды\",\"login\":\"x1\",\"email\":\"x1@example.test\",\"role\":\"LEADER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.teamId").exists());
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "partner")
                        .content("{\"displayName\":\"Вуз\",\"login\":\"x2\",\"email\":\"x2@example.test\",\"role\":\"PARTNER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").exists());
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "bad-login")
                        .content(NEW_EMPLOYEE.replace(" Petrova ", "Пётр Петров")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.login").exists());
        mockMvc.perform(post("/api/admin/crm-profiles").with(login(ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_EMPLOYEE))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/crm-profiles").with(login(ADMIN)).header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_EMPLOYEE))
                .andExpect(status().isForbidden());

        when(keycloakAccountClient.createEmployeeUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(KeycloakAccountConflictException.employee());
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "conflict").content(NEW_EMPLOYEE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("логином или почтой")));

        doThrow(new AccountSyncException("Keycloak недоступен; учётная запись не создана, повторите позже"))
                .when(keycloakAccountClient).createEmployeeUser(anyString(), anyString(), anyString(), anyString(), anyString());
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "down").content(NEW_EMPLOYEE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SYNC_FAILED"));
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "down").content(NEW_EMPLOYEE))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_user_profiles", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM command_idempotency_records", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
    }

    @Test
    void passwordResetGivesNewOneTimePasswordAndNeverToOwnAccount(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-password-reset", KAM), ADMIN, "reset-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.login").value("kam"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String password = objectMapper.readTree(body).path("temporaryPassword").asText();
        assertThat(password).hasSize(16);
        verify(keycloakAccountClient).resetTemporaryPassword("kc-" + KAM, password);

        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-password-reset", KAM), ADMIN, "reset-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        verify(keycloakAccountClient, times(1)).resetTemporaryPassword(anyString(), anyString());
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-password-reset", ADMIN), ADMIN, "reset-self"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-password-reset", UUID.randomUUID()), ADMIN, "reset-404"))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'ACCOUNT_PASSWORD_RESET'", Integer.class)).isEqualTo(1);
        assertStoredNowhere(password, output);

        doThrow(new AccountSyncException("Keycloak недоступен; учётная запись не изменена, повторите позже"))
                .when(keycloakAccountClient).resetTemporaryPassword(anyString(), anyString());
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-password-reset", LEADER), ADMIN, "reset-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("не изменена")));
    }

    @Test
    void endingSessionsLogsOutOfKeycloakAndDropsCrmSessions() throws Exception {
        insertSession("kam");
        insertSession("kam");
        insertSession("leader");

        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-logout", KAM), ADMIN, "logout-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(KAM.toString()));

        verify(keycloakAccountClient).logout("kc-" + KAM);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM spring_session WHERE principal_name = 'kam'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM spring_session WHERE principal_name = 'leader'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT details FROM audit_events WHERE action = 'ACCOUNT_SESSIONS_ENDED'", String.class))
                .contains("сеансов CRM завершено: 2");
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-logout", ADMIN), ADMIN, "logout-self"))
                .andExpect(status().isBadRequest());

        doThrow(new AccountSyncException("Keycloak недоступен; сеансы не завершены, повторите позже"))
                .when(keycloakAccountClient).logout(anyString());
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-logout", LEADER), ADMIN, "logout-down"))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM spring_session WHERE principal_name = 'leader'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void newAdministratorMustUseSecondFactorAndFailureIsShownWithoutLosingTheAccount() throws Exception {
        String admin = NEW_EMPLOYEE.replace("\"role\":\"USER\"", "\"role\":\"ADMIN\"");
        when(keycloakAccountClient.configured()).thenReturn(true);
        when(keycloakAccountClient.createEmployeeUser(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("kc-petrova", "kc-petrova-2", "kc-ivanova");

        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "create-admin").content(admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile.accountSyncError").doesNotExist());
        verify(keycloakAccountClient).setPrivileged("kc-petrova", true);

        jdbcTemplate.update("DELETE FROM crm_user_profiles WHERE login = 'petrova'");
        doThrow(new AccountSyncException("Keycloak недоступен; учётная запись не изменена, повторите позже"))
                .when(keycloakAccountClient).setPrivileged(anyString(), anyBoolean());
        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "create-admin-down").content(admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.profile.accountSyncError").value(containsString("Keycloak недоступен")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT details FROM audit_events WHERE action = 'ACCOUNT_SYNC_FAILED'", String.class))
                .startsWith("второй фактор не назначен");

        mockMvc.perform(command(post("/api/admin/crm-profiles"), ADMIN, "create-kam")
                        .content(NEW_EMPLOYEE.replace(" Petrova ", "ivanova").replace("Petrova@", "Ivanova@")))
                .andExpect(status().isCreated());
        verify(keycloakAccountClient, times(2)).setPrivileged(anyString(), anyBoolean());
    }

    @Test
    void privilegeChangeIsSavedOnlyTogetherWithTheSecondFactorRequirementInKeycloak() throws Exception {
        when(keycloakAccountClient.configured()).thenReturn(true);

        mockMvc.perform(command(patch("/api/admin/crm-profiles/{id}", KAM), ADMIN, "operator-on")
                        .content("{\"version\":0,\"enrolmentOperator\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolmentOperator").value(true));
        verify(keycloakAccountClient).setPrivileged("kc-" + KAM, true);

        mockMvc.perform(command(patch("/api/admin/crm-profiles/{id}", LEADER), ADMIN, "rename")
                        .content("{\"version\":0,\"displayName\":\"Руководитель А\"}"))
                .andExpect(status().isOk());
        verify(keycloakAccountClient, never()).setPrivileged(eq("kc-" + LEADER), anyBoolean());

        doThrow(new AccountSyncException("Keycloak недоступен; учётная запись не изменена, повторите позже"))
                .when(keycloakAccountClient).setPrivileged(anyString(), anyBoolean());
        int version = jdbcTemplate.queryForObject("SELECT version FROM crm_user_profiles WHERE id = ?", Integer.class, LEADER);
        mockMvc.perform(command(patch("/api/admin/crm-profiles/{id}", LEADER), ADMIN, "operator-down")
                        .content("{\"version\":" + version + ",\"enrolmentOperator\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SYNC_FAILED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enrolment_operator FROM crm_user_profiles WHERE id = ?", Boolean.class, LEADER)).isFalse();
    }

    @Test
    void secondFactorResetUnbindsTheAppAndKeepsItRequiredForPrivilegedAccounts() throws Exception {
        when(keycloakAccountClient.removeSecondFactor("kc-" + LEADER)).thenReturn(1);
        jdbcTemplate.update("UPDATE crm_user_profiles SET enrolment_operator = TRUE WHERE id = ?", LEADER);

        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-second-factor-reset", LEADER), ADMIN, "otp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LEADER.toString()));
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-second-factor-reset", LEADER), ADMIN, "otp-1"))
                .andExpect(status().isOk());
        verify(keycloakAccountClient, times(1)).removeSecondFactor("kc-" + LEADER);
        verify(keycloakAccountClient).setPrivileged("kc-" + LEADER, true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT details FROM audit_events WHERE action = 'ACCOUNT_SECOND_FACTOR_RESET'", String.class))
                .isEqualTo("отвязано приложений: 1; при входе потребуется подключить заново");

        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-second-factor-reset", KAM), ADMIN, "otp-kam"))
                .andExpect(status().isOk());
        verify(keycloakAccountClient).removeSecondFactor("kc-" + KAM);
        verify(keycloakAccountClient, never()).setPrivileged(eq("kc-" + KAM), anyBoolean());
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-second-factor-reset", ADMIN), ADMIN, "otp-self"))
                .andExpect(status().isBadRequest());

        doThrow(new AccountSyncException("Keycloak недоступен; учётная запись не изменена, повторите позже"))
                .when(keycloakAccountClient).removeSecondFactor(anyString());
        mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-second-factor-reset", MANAGEMENT), ADMIN, "otp-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SYNC_FAILED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'ACCOUNT_SECOND_FACTOR_RESET'", Integer.class)).isEqualTo(2);
    }

    @Test
    void emailIsChangedForEmployeesButNotForUniversityRepresentatives() throws Exception {
        mockMvc.perform(command(put("/api/admin/crm-profiles/{id}/account-email", KAM), ADMIN, "email-1")
                        .content("{\"email\":\" New.Kam@Example.test \"}"))
                .andExpect(status().isOk());
        verify(keycloakAccountClient).changeEmail("kc-" + KAM, "new.kam@example.test");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'ACCOUNT_EMAIL_CHANGED' AND object_id = ?", Integer.class, KAM))
                .isEqualTo(1);

        mockMvc.perform(command(put("/api/admin/crm-profiles/{id}/account-email", PARTNER), ADMIN, "email-partner")
                        .content("{\"email\":\"vuz@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
        mockMvc.perform(command(put("/api/admin/crm-profiles/{id}/account-email", KAM), ADMIN, "email-bad")
                        .content("{\"email\":\"не почта\"}"))
                .andExpect(status().isBadRequest());
        doThrow(KeycloakAccountConflictException.employee()).when(keycloakAccountClient).changeEmail(anyString(), anyString());
        mockMvc.perform(command(put("/api/admin/crm-profiles/{id}/account-email", LEADER), ADMIN, "email-taken")
                        .content("{\"email\":\"taken@example.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CONFLICT"));
        verify(keycloakAccountClient, never()).changeEmail(eq("kc-" + PARTNER), anyString());
    }

    @Test
    void onlyAdministratorManagesAccounts() throws Exception {
        for (UUID actor : new UUID[] {KAM, LEADER, MANAGEMENT, PARTNER}) {
            mockMvc.perform(command(post("/api/admin/crm-profiles"), actor, "create-" + actor).content(NEW_EMPLOYEE))
                    .andExpect(status().isForbidden());
            mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-password-reset", ADMIN), actor, "reset-" + actor))
                    .andExpect(status().isForbidden());
            mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-logout", ADMIN), actor, "logout-" + actor))
                    .andExpect(status().isForbidden());
            mockMvc.perform(command(post("/api/admin/crm-profiles/{id}/account-second-factor-reset", ADMIN), actor, "otp-" + actor))
                    .andExpect(status().isForbidden());
            mockMvc.perform(command(put("/api/admin/crm-profiles/{id}/account-email", ADMIN), actor, "email-" + actor)
                            .content("{\"email\":\"admin@example.test\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(keycloakAccountClient, never()).createEmployeeUser(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(keycloakAccountClient, never()).resetTemporaryPassword(anyString(), anyString());
        verify(keycloakAccountClient, never()).logout(anyString());
        verify(keycloakAccountClient, never()).changeEmail(anyString(), anyString());
        verify(keycloakAccountClient, never()).removeSecondFactor(anyString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
    }

    private void assertStoredNowhere(String password, CapturedOutput output) {
        assertThat(jdbcTemplate.queryForList("SELECT CONCAT(COALESCE(details, ''), COALESCE(object_name, '')) FROM audit_events", String.class))
                .noneMatch(value -> value.contains(password));
        assertThat(jdbcTemplate.queryForList("SELECT COALESCE(result_json, '') FROM command_idempotency_records", String.class))
                .noneMatch(value -> value.contains(password));
        assertThat(output.getAll()).doesNotContain(password);
    }

    private MockHttpServletRequestBuilder command(MockHttpServletRequestBuilder request, UUID actor, String idempotencyKey) {
        return request.with(login(actor)).with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private RequestPostProcessor login(UUID profileId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("kc-" + profileId));
    }

    private void insertProfile(UUID id, String displayName, String role, UUID teamId, String login) {
        jdbcTemplate.update("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, login, role, team_id, active, pending_activation)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, FALSE)
                """, id, ISSUER, "kc-" + id, displayName, login, role, teamId);
    }

    private void insertSession(String principal) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO spring_session (
                    primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name
                ) VALUES (?, ?, 0, 0, 1800, 9999999999999, ?)
                """, id, id, principal);
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
                    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL
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
                """
        };
        for (String statement : statements) {
            jdbcTemplate.execute(statement);
        }
    }
}
