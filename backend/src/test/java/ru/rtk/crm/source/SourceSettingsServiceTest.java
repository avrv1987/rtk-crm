package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.unit.DataSize;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;

@JdbcTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:source-settings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SourceSettingsService.class,
        SourceSettingsRepository.class,
        SourceTokenCipher.class,
        AuditJournalRepository.class,
        SourceSettingsServiceTest.SettingsTestConfiguration.class
})
class SourceSettingsServiceTest {
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String KEY = Base64.getEncoder().encodeToString("k".repeat(32).getBytes(StandardCharsets.UTF_8));
    private static final String ENV_MOODLE = "http://moodle:8080";
    private static final String ENV_MOODLE_TOKEN = "env-moodle-token-value";
    private static final String NEW_MOODLE_TOKEN = "screen-moodle-token-value";
    private static final String NEW_SITE_TOKEN = "screen-site-token-value";
    private static final String NEW_CRM_TOKEN = "screen-crm-token-value";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile leader = new CrmProfile(LEADER, UserRole.LEADER, null, 0);

    @Autowired
    private SourceSettingsService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MoodleClient moodleClient;

    @MockitoBean
    private SiteApiClient siteApiClient;

    @MockitoBean
    private CrmClient crmClient;

    @BeforeEach
    void setUp() {
        List.of(
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL, 
                    id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS source_settings (
                    id SMALLINT PRIMARY KEY, moodle_base_url VARCHAR(500), moodle_token_encrypted VARCHAR(2000),
                    moodle_token_changed_at TIMESTAMP WITH TIME ZONE, moodle_course_ids VARCHAR(2000) NOT NULL,
                    moodle_student_roles VARCHAR(500) NOT NULL, moodle_teacher_roles VARCHAR(500) NOT NULL,
                    website_base_url VARCHAR(500), website_token_encrypted VARCHAR(2000),
                    website_token_changed_at TIMESTAMP WITH TIME ZONE, sync_cron VARCHAR(100), crm_base_url VARCHAR(500),
                    crm_token_encrypted VARCHAR(2000), crm_token_changed_at TIMESTAMP WITH TIME ZONE,
                    updated_by UUID NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    id UUID PRIMARY KEY, category VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL, actor_profile_id UUID,
                    actor_display_name VARCHAR(200) NOT NULL, object_type VARCHAR(32), object_id UUID,
                    object_name VARCHAR(500), details VARCHAR(2000), request_id VARCHAR(64),
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """
        ).forEach(jdbcTemplate::execute);
        jdbcTemplate.update("DELETE FROM audit_events");
        jdbcTemplate.update("DELETE FROM source_settings");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, display_name) VALUES (?, 'Администратор'), (?, 'Руководитель')",
                ADMIN, LEADER);
    }

    @Test
    void environmentValuesAreInitialAndSavedValuesTakePriority() {
        SourceSettingsView initial = service.view(admin);
        assertThat(initial.saved()).isFalse();
        assertThat(initial.moodleBaseUrl()).isEqualTo(ENV_MOODLE);
        assertThat(initial.moodleCourseIds()).containsExactly(2L, 3L);
        assertThat(initial.moodleToken().origin()).isEqualTo(TokenOrigin.ENVIRONMENT);
        assertThat(initial.websiteToken().origin()).isEqualTo(TokenOrigin.NONE);
        assertThat(initial.syncCron()).isEqualTo("0 0 * * * *");
        assertThat(service.moodle().token()).isEqualTo(ENV_MOODLE_TOKEN);

        SourceSettingsView saved = service.update(admin, request("https://lms.example.test", NEW_MOODLE_TOKEN, List.of(7L, 7L, 8L),
                "https://site.example.test", null, ""), "request-1");

        assertThat(saved.saved()).isTrue();
        assertThat(saved.updatedByName()).isEqualTo("Администратор");
        assertThat(saved.moodleCourseIds()).containsExactly(7L, 8L);
        assertThat(saved.syncCron()).isNull();
        assertThat(saved.moodleToken().origin()).isEqualTo(TokenOrigin.SCREEN);
        assertThat(service.moodle().baseUrl()).isEqualTo("https://lms.example.test");
        assertThat(service.moodle().token()).isEqualTo(NEW_MOODLE_TOKEN);
        assertThat(service.moodle().courseIds()).containsExactly(7L, 8L);
        assertThat(service.website().baseUrl()).isEqualTo("https://site.example.test");
        assertThat(service.syncCron()).isNull();

        service.reset(admin, "request-2");

        assertThat(service.view(admin).saved()).isFalse();
        assertThat(service.moodle().baseUrl()).isEqualTo(ENV_MOODLE);
        assertThat(service.syncCron()).isEqualTo("0 0 * * * *");
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class))
                .containsOnly("SOURCE_SETTINGS_CHANGED").hasSize(2);
    }

    @Test
    void tokensAreEncryptedAndNeverReturnedOrJournaled() throws Exception {
        SourceSettingsView view = service.update(admin, request(ENV_MOODLE, NEW_MOODLE_TOKEN, List.of(2L),
                "https://site.example.test", NEW_SITE_TOKEN, "0 30 6 * * *"), "request-1");

        String stored = jdbcTemplate.queryForObject("SELECT moodle_token_encrypted FROM source_settings", String.class);
        assertThat(stored).doesNotContain(NEW_MOODLE_TOKEN);
        assertThat(new SourceTokenCipher(properties(KEY)).decrypt(stored, SourceSettingsService.MOODLE_TOKEN_CONTEXT))
                .contains(NEW_MOODLE_TOKEN);
        assertThat(new SourceTokenCipher(properties(KEY)).decrypt(stored, SourceSettingsService.WEBSITE_TOKEN_CONTEXT)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT website_token_encrypted FROM source_settings", String.class))
                .doesNotContain(NEW_SITE_TOKEN);
        assertThat(service.moodle().token()).isEqualTo(NEW_MOODLE_TOKEN);
        assertThat(service.website().token()).isEqualTo(NEW_SITE_TOKEN);
        assertThat(view.moodleToken().origin()).isEqualTo(TokenOrigin.SCREEN);
        assertThat(view.moodleToken().changedAt()).isNotNull();
        assertThat(view.websiteToken().origin()).isEqualTo(TokenOrigin.SCREEN);
        assertThat(JSON.writeValueAsString(view)).doesNotContain(NEW_MOODLE_TOKEN, NEW_SITE_TOKEN, ENV_MOODLE_TOKEN);
        assertThat(JSON.writeValueAsString(service.view(admin))).doesNotContain(NEW_MOODLE_TOKEN, NEW_SITE_TOKEN, ENV_MOODLE_TOKEN);
        String details = jdbcTemplate.queryForObject("SELECT details FROM audit_events", String.class);
        assertThat(details).contains("токен Moodle заменён", "токен сайта заменён", "курсы Moodle: 2, 3 → 2",
                "расписание: 0 0 * * * * → 0 30 6 * * *").doesNotContain(NEW_MOODLE_TOKEN, NEW_SITE_TOKEN, ENV_MOODLE_TOKEN);

        service.update(admin, request(ENV_MOODLE, " ", List.of(2L), "https://site.example.test", null, "0 30 6 * * *"),
                "request-2");

        assertThat(service.moodle().token()).isEqualTo(NEW_MOODLE_TOKEN);
        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE request_id = 'request-2'", String.class))
                .containsExactly("значения сохранены без изменений");
    }

    @Test
    void crmAddressAndTokenAreSavedEncryptedAndChecked() throws Exception {
        assertThat(service.view(admin).crmBaseUrl()).isNull();
        assertThat(service.view(admin).crmToken().origin()).isEqualTo(TokenOrigin.NONE);

        SourceSettingsView view = service.update(admin, crmRequest("https://crm.example.test", NEW_CRM_TOKEN), "request-1");

        String stored = jdbcTemplate.queryForObject("SELECT crm_token_encrypted FROM source_settings", String.class);
        assertThat(stored).doesNotContain(NEW_CRM_TOKEN);
        assertThat(new SourceTokenCipher(properties(KEY)).decrypt(stored, SourceSettingsService.CRM_TOKEN_CONTEXT))
                .contains(NEW_CRM_TOKEN);
        assertThat(new SourceTokenCipher(properties(KEY)).decrypt(stored, SourceSettingsService.WEBSITE_TOKEN_CONTEXT)).isEmpty();
        assertThat(view.crmBaseUrl()).isEqualTo("https://crm.example.test");
        assertThat(view.crmToken().origin()).isEqualTo(TokenOrigin.SCREEN);
        assertThat(JSON.writeValueAsString(view)).doesNotContain(NEW_CRM_TOKEN);
        assertThat(service.crm().token()).isEqualTo(NEW_CRM_TOKEN);
        assertThat(jdbcTemplate.queryForObject("SELECT details FROM audit_events", String.class))
                .contains("токен CRM заказчика заменён", "адрес CRM заказчика: не задан → https://crm.example.test")
                .doesNotContain(NEW_CRM_TOKEN);

        SourceConnectionCheck ok = service.check(admin, SourceCode.CUSTOMER_CRM, crmRequest("https://crm.example.test", null));
        ArgumentCaptor<SourceProperties.Crm> checked = ArgumentCaptor.forClass(SourceProperties.Crm.class);
        verify(crmClient).check(checked.capture());
        assertThat(checked.getValue().token()).isEqualTo(NEW_CRM_TOKEN);
        assertThat(ok.ok()).isTrue();

        assertThat(service.check(admin, SourceCode.CUSTOMER_CRM, crmRequest(null, null)).ok()).isFalse();
        assertInvalid(crmRequest("http://crm.example.test", null), "crmBaseUrl");
        assertInvalid(crmRequest("https://crm.example.test", "two words"), "crmToken");
        assertThatThrownBy(() -> service.update(leader, crmRequest("https://crm.example.test", null), "request"))
                .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void storedTokenUnreadableWithAnotherKeyIsNotUsed() {
        service.update(admin, request(ENV_MOODLE, NEW_MOODLE_TOKEN, List.of(2L), null, null, null), "request-1");
        jdbcTemplate.update("UPDATE source_settings SET moodle_token_encrypted = ?",
                new SourceTokenCipher(properties(Base64.getEncoder().encodeToString(new byte[32])))
                        .encrypt(NEW_MOODLE_TOKEN, SourceSettingsService.MOODLE_TOKEN_CONTEXT));

        assertThat(service.view(admin).moodleToken().origin()).isEqualTo(TokenOrigin.UNREADABLE);
        assertThat(service.moodle().token()).isNull();
        assertThat(service.moodle().configured()).isFalse();
    }

    @Test
    void fieldsAreValidated() {
        assertInvalid(request("http://lms.example.test", null, List.of(2L), null, null, null), "moodleBaseUrl");
        assertInvalid(request("https://user:pass@lms.example.test", null, List.of(2L), null, null, null), "moodleBaseUrl");
        assertInvalid(request("https://lms.example.test?x=1", null, List.of(2L), null, null, null), "moodleBaseUrl");
        assertInvalid(request(ENV_MOODLE, null, List.of(0L), null, null, null), "moodleCourseIds");
        assertInvalid(request(ENV_MOODLE, "two words", List.of(2L), null, null, null), "moodleToken");
        assertInvalid(request(ENV_MOODLE, null, List.of(2L), "ftp://site.example.test", null, null), "websiteBaseUrl");
        assertInvalid(request(ENV_MOODLE, null, List.of(2L), null, null, "0 0 * * *"), "syncCron");
        assertInvalid(request(ENV_MOODLE, null, List.of(2L), null, null, "0 * * * * *"), "syncCron");
        assertInvalid(new SourceSettingsRequest(ENV_MOODLE, null, List.of(2L), List.of(), List.of(), null, null, null),
                "moodleStudentRoles");
        assertInvalid(new SourceSettingsRequest(ENV_MOODLE, null, List.of(2L), List.of("Student Role"), List.of(), null, null, null),
                "moodleStudentRoles");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_settings", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
    }

    @Test
    void onlyAdministratorReadsChangesAndChecksSettings() {
        SourceSettingsRequest request = request(ENV_MOODLE, NEW_MOODLE_TOKEN, List.of(2L), null, null, null);
        for (Runnable call : List.<Runnable>of(
                () -> service.view(leader),
                () -> service.update(leader, request, "request"),
                () -> service.reset(leader, "request"),
                () -> service.check(leader, SourceCode.MOODLE, request)
        )) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_settings", Integer.class)).isZero();
        verify(moodleClient, never()).check(any());
    }

    @Test
    void connectionCheckUsesFormValuesWithoutSavingAndExplainsFailure() {
        when(moodleClient.check(any())).thenReturn(List.of("2 «Java-разработчик»"));

        SourceConnectionCheck ok = service.check(admin, SourceCode.MOODLE,
                request("https://lms.example.test", NEW_MOODLE_TOKEN, List.of(2L), null, null, null));

        ArgumentCaptor<SourceProperties.Moodle> checked = ArgumentCaptor.forClass(SourceProperties.Moodle.class);
        verify(moodleClient).check(checked.capture());
        assertThat(checked.getValue().baseUrl()).isEqualTo("https://lms.example.test");
        assertThat(checked.getValue().token()).isEqualTo(NEW_MOODLE_TOKEN);
        assertThat(checked.getValue().courseIds()).containsExactly(2L);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.details()).containsExactly("2 «Java-разработчик»");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_settings", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();

        when(moodleClient.check(any())).thenThrow(SourceFetchException.unauthorized(
                "Moodle отклонил запрос core_course_get_courses_by_field (invalidtoken); проверьте токен Moodle"));
        SourceConnectionCheck failed = service.check(admin, SourceCode.MOODLE,
                request("https://lms.example.test", NEW_MOODLE_TOKEN, List.of(2L), null, null, null));

        assertThat(failed.ok()).isFalse();
        assertThat(failed.message()).contains("invalidtoken").doesNotContain(NEW_MOODLE_TOKEN);

        SourceConnectionCheck incomplete = service.check(admin, SourceCode.MOODLE,
                request("https://lms.example.test", NEW_MOODLE_TOKEN, List.of(), null, null, null));
        assertThat(incomplete.ok()).isFalse();
        assertThat(incomplete.message()).contains("хотя бы один курс");

        SourceConnectionCheck site = service.check(admin, SourceCode.WEBSITE,
                request(ENV_MOODLE, null, List.of(2L), "https://site.example.test", NEW_SITE_TOKEN, null));
        ArgumentCaptor<SourceProperties.Website> website = ArgumentCaptor.forClass(SourceProperties.Website.class);
        verify(siteApiClient).check(website.capture());
        assertThat(website.getValue().token()).isEqualTo(NEW_SITE_TOKEN);
        assertThat(site.ok()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM source_settings", Integer.class)).isZero();
    }

    @Test
    void savedTokenNeverGoesToAnotherAddressOfMoodleWebsiteOrCustomerCrm() {
        for (boolean saved : List.of(false, true)) {
            jdbcTemplate.update("DELETE FROM source_settings");
            if (saved) {
                service.update(admin, new SourceSettingsRequest("https://lms.example.test", NEW_MOODLE_TOKEN, List.of(2L),
                        List.of("student"), List.of("teacher"), "https://site.example.test", NEW_SITE_TOKEN, null,
                        "https://crm.example.test", NEW_CRM_TOKEN), "saved-token");
            }
            String moodleUrl = saved ? "https://lms.example.test" : ENV_MOODLE;
            String site = saved ? "https://site.example.test" : null;
            String crm = saved ? "https://crm.example.test" : null;

            assertMovedWithoutToken(new SourceSettingsRequest("https://elsewhere.example.test", null, List.of(2L), List.of("student"),
                    List.of("teacher"), site, null, null, crm, null), "moodleToken", SourceCode.MOODLE);
            if (saved) {
                assertMovedWithoutToken(new SourceSettingsRequest(moodleUrl, null, List.of(2L), List.of("student"), List.of("teacher"),
                        "https://elsewhere.example.test", null, null, crm, null), "websiteToken", SourceCode.WEBSITE);
                assertMovedWithoutToken(new SourceSettingsRequest(moodleUrl, null, List.of(2L), List.of("student"), List.of("teacher"),
                        site, null, null, "https://elsewhere.example.test", null), "crmToken", SourceCode.CUSTOMER_CRM);
            }
        }
        verify(moodleClient, never()).check(any());
        verify(siteApiClient, never()).check(any());
        verify(crmClient, never()).check(any());
        assertThat(service.moodle().baseUrl()).isEqualTo("https://lms.example.test");
        assertThat(service.moodle().token()).isEqualTo(NEW_MOODLE_TOKEN);

        service.update(admin, new SourceSettingsRequest("https://LMS.example.test/", null, List.of(2L), List.of("student"),
                List.of("teacher"), "https://site.example.test", null, null, "https://crm.example.test", null), "same-address");
        assertThat(service.moodle().token()).isEqualTo(NEW_MOODLE_TOKEN);
        service.update(admin, new SourceSettingsRequest("https://elsewhere.example.test", "typed-new-token", List.of(2L),
                List.of("student"), List.of("teacher"), null, null, null, null, null), "moved-with-token");
        assertThat(service.moodle().baseUrl()).isEqualTo("https://elsewhere.example.test");
        assertThat(service.moodle().token()).isEqualTo("typed-new-token");
        assertThat(service.website().baseUrl()).isNull();
    }

    @Test
    void checkOfNewAddressSendsOnlyTokenTypedInTheSameForm() {
        when(moodleClient.check(any())).thenReturn(List.of("2 «Java-разработчик»"));

        assertThat(service.check(admin, SourceCode.MOODLE,
                request("https://lms.example.test", "typed-token-value", List.of(2L), null, null, null)).ok()).isTrue();

        ArgumentCaptor<SourceProperties.Moodle> checked = ArgumentCaptor.forClass(SourceProperties.Moodle.class);
        verify(moodleClient).check(checked.capture());
        assertThat(checked.getValue().token()).isEqualTo("typed-token-value").isNotEqualTo(ENV_MOODLE_TOKEN);

        assertThat(service.check(admin, SourceCode.WEBSITE,
                request(ENV_MOODLE, null, List.of(2L), "https://site.example.test", null, null)).ok()).isTrue();
        verify(siteApiClient).check(any());
    }

    private void assertMovedWithoutToken(SourceSettingsRequest moved, String field, SourceCode source) {
        assertThatThrownBy(() -> service.update(admin, moved, "request"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo(field));
        SourceConnectionCheck check = service.check(admin, source, moved);
        assertThat(check.ok()).isFalse();
        assertThat(check.message()).contains("введите токен для нового адреса");
    }

    private void assertInvalid(SourceSettingsRequest request, String field) {
        assertThatThrownBy(() -> service.update(admin, request, "request"))
                .isInstanceOfSatisfying(InteractionValidationException.class, exception -> assertThat(exception.field()).isEqualTo(field));
    }

    private static SourceSettingsRequest crmRequest(String crmUrl, String crmToken) {
        return new SourceSettingsRequest(ENV_MOODLE, null, List.of(2L), List.of("student"), List.of("editingteacher", "teacher"),
                null, null, null, crmUrl, crmToken);
    }

    private static SourceSettingsRequest request(String moodleUrl, String moodleToken, List<Long> courses, String siteUrl,
                                                 String siteToken, String cron) {
        return new SourceSettingsRequest(moodleUrl, moodleToken, courses, List.of("student"), List.of("editingteacher", "teacher"),
                siteUrl, siteToken, cron);
    }

    static SourceProperties properties(String key) {
        return new SourceProperties(1, 1, "0 0 * * * *", Duration.ofHours(26), key,
                new SourceProperties.Website(null, null, Duration.ofSeconds(1), Duration.ofSeconds(1), 10, DataSize.ofMegabytes(1)),
                new SourceProperties.Moodle(ENV_MOODLE, ENV_MOODLE_TOKEN, List.of(2L, 3L), List.of("student"),
                        List.of("editingteacher", "teacher"), Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1)),
                null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SettingsTestConfiguration {
        @Bean
        SourceProperties sourceProperties() {
            return properties(KEY);
        }
    }
}
