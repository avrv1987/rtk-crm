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
        "spring.datasource.url=jdbc:h2:mem:labor-market-settings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        LaborMarketSettingsService.class,
        LaborMarketSettingsRepository.class,
        SourceTokenCipher.class,
        AuditJournalRepository.class,
        LaborMarketSettingsServiceTest.SettingsTestConfiguration.class
})
class LaborMarketSettingsServiceTest {
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String KEY = Base64.getEncoder().encodeToString("k".repeat(32).getBytes(StandardCharsets.UTF_8));
    private static final String ENV_URL = "http://labor-market-fixture:8080";
    private static final String ENV_TOKEN = "env-labor-token-value";
    private static final String NEW_TOKEN = "screen-labor-token-value";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final CrmProfile admin = new CrmProfile(ADMIN, UserRole.ADMIN, null, 0);
    private final CrmProfile leader = new CrmProfile(LEADER, UserRole.LEADER, null, 0);

    @Autowired
    private LaborMarketSettingsService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LaborMarketClient client;

    @BeforeEach
    void setUp() {
        List.of(
                """
                CREATE TABLE IF NOT EXISTS crm_user_profiles (role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE, role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL,
                    id UUID PRIMARY KEY, display_name VARCHAR(200) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS labor_market_settings (
                    id SMALLINT PRIMARY KEY, base_url VARCHAR(500), token_encrypted VARCHAR(2000),
                    token_changed_at TIMESTAMP WITH TIME ZONE, updated_by UUID NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
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
        jdbcTemplate.update("DELETE FROM labor_market_settings");
        jdbcTemplate.update("DELETE FROM crm_user_profiles");
        jdbcTemplate.update("INSERT INTO crm_user_profiles (id, display_name) VALUES (?, 'Администратор'), (?, 'Руководитель')",
                ADMIN, LEADER);
    }

    @Test
    void environmentValuesAreInitialAndSavedValuesTakePriorityUntilReset() {
        LaborMarketSettingsView initial = service.view(admin);
        assertThat(initial.saved()).isFalse();
        assertThat(initial.baseUrl()).isEqualTo(ENV_URL);
        assertThat(initial.token().origin()).isEqualTo(TokenOrigin.ENVIRONMENT);
        assertThat(service.connection().token()).isEqualTo(ENV_TOKEN);

        LaborMarketSettingsView saved = service.update(admin, new LaborMarketSettingsRequest("https://market.example.test", NEW_TOKEN),
                "request-1");

        assertThat(saved.saved()).isTrue();
        assertThat(saved.updatedByName()).isEqualTo("Администратор");
        assertThat(saved.baseUrl()).isEqualTo("https://market.example.test");
        assertThat(service.connection().baseUrl()).isEqualTo("https://market.example.test");
        assertThat(service.connection().token()).isEqualTo(NEW_TOKEN);

        service.update(admin, new LaborMarketSettingsRequest(null, null), "request-2");
        assertThat(service.connection().configured()).isFalse();

        service.reset(admin, "request-3");

        assertThat(service.view(admin).saved()).isFalse();
        assertThat(service.connection().baseUrl()).isEqualTo(ENV_URL);
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_events", String.class))
                .containsOnly("SOURCE_SETTINGS_CHANGED").hasSize(3);
    }

    @Test
    void tokenIsEncryptedAndNeverReturnedOrJournaled() throws Exception {
        LaborMarketSettingsView view = service.update(admin, new LaborMarketSettingsRequest(ENV_URL, NEW_TOKEN), "request-1");

        String stored = jdbcTemplate.queryForObject("SELECT token_encrypted FROM labor_market_settings", String.class);
        assertThat(stored).doesNotContain(NEW_TOKEN);
        assertThat(new SourceTokenCipher(properties()).decrypt(stored, LaborMarketSettingsService.TOKEN_CONTEXT)).contains(NEW_TOKEN);
        assertThat(new SourceTokenCipher(properties()).decrypt(stored, SourceSettingsService.WEBSITE_TOKEN_CONTEXT)).isEmpty();
        assertThat(service.connection().token()).isEqualTo(NEW_TOKEN);
        assertThat(view.token().origin()).isEqualTo(TokenOrigin.SCREEN);
        assertThat(view.token().changedAt()).isNotNull();
        assertThat(JSON.writeValueAsString(view)).doesNotContain(NEW_TOKEN, ENV_TOKEN);
        assertThat(JSON.writeValueAsString(service.view(admin))).doesNotContain(NEW_TOKEN, ENV_TOKEN);
        assertThat(jdbcTemplate.queryForObject("SELECT details FROM audit_events", String.class))
                .contains("токен рынка труда заменён").doesNotContain(NEW_TOKEN, ENV_TOKEN);

        service.update(admin, new LaborMarketSettingsRequest(ENV_URL, " "), "request-2");

        assertThat(service.connection().token()).isEqualTo(NEW_TOKEN);
        assertThat(jdbcTemplate.queryForList("SELECT details FROM audit_events WHERE request_id = 'request-2'", String.class))
                .containsExactly("значения сохранены без изменений");

        jdbcTemplate.update("UPDATE labor_market_settings SET token_encrypted = ?",
                new SourceTokenCipher(otherKey()).encrypt(NEW_TOKEN, LaborMarketSettingsService.TOKEN_CONTEXT));

        assertThat(service.view(admin).token().origin()).isEqualTo(TokenOrigin.UNREADABLE);
        assertThat(service.connection().token()).isNull();
    }

    @Test
    void addressAndTokenAreValidated() {
        for (LaborMarketSettingsRequest request : List.of(
                new LaborMarketSettingsRequest("http://market.example.test", null),
                new LaborMarketSettingsRequest("https://user:pass@market.example.test", null),
                new LaborMarketSettingsRequest("https://market.example.test?x=1", null),
                new LaborMarketSettingsRequest("ftp://market.example.test", null),
                new LaborMarketSettingsRequest(ENV_URL, "two words")
        )) {
            assertThatThrownBy(() -> service.update(admin, request, "request"))
                    .isInstanceOf(InteractionValidationException.class);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM labor_market_settings", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
    }

    @Test
    void onlyAdministratorReadsChangesAndChecksSettings() {
        LaborMarketSettingsRequest request = new LaborMarketSettingsRequest(ENV_URL, NEW_TOKEN);
        for (Runnable call : List.<Runnable>of(
                () -> service.view(leader),
                () -> service.update(leader, request, "request"),
                () -> service.reset(leader, "request"),
                () -> service.check(leader, request)
        )) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(SourceException.class, exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM labor_market_settings", Integer.class)).isZero();
        verify(client, never()).check(any());
    }

    @Test
    void connectionCheckUsesFormValuesWithoutSavingAndExplainsFailure() {
        LaborMarketItem item = new LaborMarketItem("dev", "Разработка", java.time.YearMonth.of(2026, 9), 10, null, "Источник",
                java.time.LocalDate.of(2026, 9, 30));
        when(client.check(any())).thenReturn(new LaborMarketClient.Batch(List.of(item), 0));

        SourceConnectionCheck ok = service.check(admin, new LaborMarketSettingsRequest("https://market.example.test", NEW_TOKEN));

        ArgumentCaptor<LaborMarketProperties> checked = ArgumentCaptor.forClass(LaborMarketProperties.class);
        verify(client).check(checked.capture());
        assertThat(checked.getValue().baseUrl()).isEqualTo("https://market.example.test");
        assertThat(checked.getValue().token()).isEqualTo(NEW_TOKEN);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.details()).containsExactly("Направлений в ответе: 1", "Источник данных: Источник");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM labor_market_settings", Integer.class)).isZero();

        when(client.check(any())).thenThrow(SourceFetchException.unauthorized(
                "Источник рынка труда отклонил токен доступа; проверьте токен"));
        SourceConnectionCheck failed = service.check(admin, new LaborMarketSettingsRequest(ENV_URL, NEW_TOKEN));

        assertThat(failed.ok()).isFalse();
        assertThat(failed.message()).contains("проверьте токен").doesNotContain(NEW_TOKEN);

        SourceConnectionCheck incomplete = service.check(admin, new LaborMarketSettingsRequest(null, null));
        assertThat(incomplete.ok()).isFalse();
        assertThat(incomplete.message()).contains("укажите адрес");
    }

    @Test
    void savedTokenNeverGoesToAnotherAddress() {
        for (String environmentOrSaved : List.of("environment", "saved")) {
            if (environmentOrSaved.equals("saved")) {
                service.update(admin, new LaborMarketSettingsRequest(ENV_URL, NEW_TOKEN), "saved-token");
            }
            LaborMarketSettingsRequest moved = new LaborMarketSettingsRequest("https://other.example.test", null);

            assertThatThrownBy(() -> service.update(admin, moved, "request"))
                    .isInstanceOfSatisfying(InteractionValidationException.class,
                            exception -> assertThat(exception.field()).isEqualTo("token"));
            SourceConnectionCheck check = service.check(admin, moved);

            assertThat(check.ok()).isFalse();
            assertThat(check.message()).contains("введите токен для нового адреса");
            assertThat(service.connection().baseUrl()).isEqualTo(ENV_URL);
        }
        verify(client, never()).check(any());

        service.update(admin, new LaborMarketSettingsRequest(null, null), "cleared");
        service.update(admin, new LaborMarketSettingsRequest("https://other.example.test", "another-token-value"), "new-token");

        assertThat(service.connection().baseUrl()).isEqualTo("https://other.example.test");
        assertThat(service.connection().token()).isEqualTo("another-token-value");
    }

    @Test
    void clientAcceptsOptionalSalaryAndRejectsBrokenOrDuplicateItems() {
        LaborMarketClient parser = new LaborMarketClient(laborProperties(), JSON);
        String body = """
                {"items":[
                  {"directionCode":"dev","directionName":" Разработка ПО ","month":"2026-09","openVacancies":120,
                   "dataSource":"Источник","asOf":"2026-09-30"},
                  {"directionCode":"dev","directionName":"Разработка ПО","month":"2026-10","openVacancies":130,"medianSalary":200000,
                   "dataSource":"Источник","asOf":"2026-10-15"},
                  {"directionCode":"dev","directionName":"Разработка ПО","month":"2026-10","openVacancies":131,
                   "dataSource":"Источник","asOf":"2026-10-16"},
                  {"directionCode":"dev","directionName":"Разработка ПО","month":"2026-10-01","openVacancies":1,
                   "dataSource":"Источник","asOf":"2026-10-15"},
                  {"directionCode":"dev","directionName":"Разработка ПО","month":"2026-08","openVacancies":1.5,
                   "dataSource":"Источник","asOf":"2026-08-31"},
                  {"directionCode":"dev","directionName":"Разработка ПО","month":"2026-07","openVacancies":1,
                   "medianSalary":0,"dataSource":"Источник","asOf":"2026-07-31"},
                  {"directionCode":"dev","directionName":"","month":"2026-06","openVacancies":1,"dataSource":"Источник",
                   "asOf":"2026-06-30"},
                  {"directionCode":"dev","directionName":"Разработка ПО","month":"2026-05","openVacancies":1,"dataSource":"Источник",
                   "asOf":"31.05.2026"},
                  "строка"
                ]}
                """;

        LaborMarketClient.Batch batch = parser.parse(laborProperties(), body.getBytes(StandardCharsets.UTF_8));

        assertThat(batch.items()).extracting(LaborMarketItem::month, LaborMarketItem::openVacancies, LaborMarketItem::medianSalary,
                LaborMarketItem::directionName).containsExactly(
                org.assertj.core.groups.Tuple.tuple(java.time.YearMonth.of(2026, 9), 120, null, "Разработка ПО"),
                org.assertj.core.groups.Tuple.tuple(java.time.YearMonth.of(2026, 10), 130, 200000, "Разработка ПО"));
        assertThat(batch.rejected()).isEqualTo(7);
        assertThatThrownBy(() -> parser.parse(laborProperties(), "[]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SourceFetchException.class);
        assertThatThrownBy(() -> parser.parse(laborProperties(), "не json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SourceFetchException.class);
    }

    private static LaborMarketProperties laborProperties() {
        return new LaborMarketProperties(ENV_URL, ENV_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1), 100,
                24);
    }

    private static SourceProperties properties() {
        return properties(KEY);
    }

    private static SourceProperties otherKey() {
        return properties(Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static SourceProperties properties(String key) {
        return new SourceProperties(1, 1, "0 0 * * * *", Duration.ofHours(26), key,
                new SourceProperties.Website(null, null, Duration.ofSeconds(1), Duration.ofSeconds(1), 10, DataSize.ofMegabytes(1)),
                new SourceProperties.Moodle(null, null, List.of(), List.of("student"), List.of(), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), DataSize.ofMegabytes(1)),
                null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SettingsTestConfiguration {
        @Bean
        SourceProperties sourceProperties() {
            return properties(KEY);
        }

        @Bean
        LaborMarketProperties laborMarketProperties() {
            return laborProperties();
        }
    }
}
