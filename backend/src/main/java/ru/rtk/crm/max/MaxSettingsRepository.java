package ru.rtk.crm.max;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@MaxEnabled
public class MaxSettingsRepository {
    private final JdbcClient jdbcClient;

    public MaxSettingsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Stored find() {
        return jdbcClient.sql("""
                SELECT token_encrypted, webhook_secret_encrypted, bot_name, mini_app_enabled, changed_at, subscribed_at,
                       subscription_checked_at, subscription_ok, subscription_error, last_event_at, events_collected_until
                FROM max_settings
                WHERE id = 1
                """)
                .query((rs, row) -> new Stored(
                        rs.getString("token_encrypted"),
                        rs.getString("webhook_secret_encrypted"),
                        rs.getString("bot_name"),
                        rs.getBoolean("mini_app_enabled"),
                        rs.getObject("changed_at", OffsetDateTime.class),
                        rs.getObject("subscribed_at", OffsetDateTime.class),
                        rs.getObject("subscription_checked_at", OffsetDateTime.class),
                        (Boolean) rs.getObject("subscription_ok"),
                        rs.getString("subscription_error"),
                        rs.getObject("last_event_at", OffsetDateTime.class),
                        rs.getObject("events_collected_until", OffsetDateTime.class)
                ))
                .single();
    }

    // Новый токен — новый бот или перевыпуск: имя бота и подписка сбрасываются, пока администратор не проверит связь
    // и не подпишет вебхук заново.
    public void saveToken(String tokenEncrypted, String secretEncrypted, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE max_settings
                SET token_encrypted = :token, webhook_secret_encrypted = :secret, bot_name = CAST(NULL AS VARCHAR(100)),
                    subscribed_at = CAST(NULL AS TIMESTAMP WITH TIME ZONE), subscription_ok = CAST(NULL AS BOOLEAN),
                    subscription_checked_at = CAST(NULL AS TIMESTAMP WITH TIME ZONE),
                    subscription_error = CAST(NULL AS VARCHAR(500)), changed_at = :now
                WHERE id = 1
                """)
                .param("token", tokenEncrypted)
                .param("secret", secretEncrypted)
                .param("now", now)
                .update();
    }

    // Токен из окружения пишется только в пустую строку: условие в самом UPDATE, чтобы сохранённый администратором
    // токен не перетёрся, даже если он появился между чтением и записью.
    public boolean saveInitialToken(String tokenEncrypted, String secretEncrypted, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE max_settings
                SET token_encrypted = :token, webhook_secret_encrypted = :secret, changed_at = :now
                WHERE id = 1 AND token_encrypted IS NULL
                """)
                .param("token", tokenEncrypted)
                .param("secret", secretEncrypted)
                .param("now", now)
                .update() == 1;
    }

    public void saveMiniApp(boolean enabled, OffsetDateTime now) {
        jdbcClient.sql("UPDATE max_settings SET mini_app_enabled = :enabled, changed_at = :now WHERE id = 1")
                .param("enabled", enabled)
                .param("now", now)
                .update();
    }

    public void saveBotName(String botName) {
        jdbcClient.sql("UPDATE max_settings SET bot_name = :botName WHERE id = 1")
                .param("botName", botName)
                .update();
    }

    public void markSubscribed(OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE max_settings
                SET subscribed_at = :now, subscription_checked_at = :now, subscription_ok = TRUE,
                    subscription_error = CAST(NULL AS VARCHAR(500))
                WHERE id = 1
                """)
                .param("now", now)
                .update();
    }

    public void markSubscriptionChecked(boolean ok, String error, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE max_settings
                SET subscription_checked_at = :now, subscription_ok = :ok, subscription_error = :error
                WHERE id = 1
                """)
                .param("now", now)
                .param("ok", ok)
                .param("error", error == null ? null : truncate(error))
                .update();
    }

    public void touchLastEvent(OffsetDateTime now) {
        jdbcClient.sql("UPDATE max_settings SET last_event_at = :now WHERE id = 1")
                .param("now", now)
                .update();
    }

    public void saveCollectedUntil(OffsetDateTime until) {
        jdbcClient.sql("UPDATE max_settings SET events_collected_until = :until WHERE id = 1")
                .param("until", until)
                .update();
    }

    static String truncate(String value) {
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public record Stored(
            String tokenEncrypted,
            String secretEncrypted,
            String botName,
            boolean miniAppEnabled,
            OffsetDateTime changedAt,
            OffsetDateTime subscribedAt,
            OffsetDateTime subscriptionCheckedAt,
            Boolean subscriptionOk,
            String subscriptionError,
            OffsetDateTime lastEventAt,
            OffsetDateTime eventsCollectedUntil
    ) {
    }
}
