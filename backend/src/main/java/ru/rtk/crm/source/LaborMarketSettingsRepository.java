package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class LaborMarketSettingsRepository {
    private final JdbcClient jdbcClient;

    LaborMarketSettingsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<StoredSettings> find() {
        return jdbcClient.sql("""
                        SELECT s.base_url, s.token_encrypted, s.token_changed_at, s.updated_at, p.display_name AS updated_by_name
                        FROM labor_market_settings s
                        LEFT JOIN crm_user_profiles p ON p.id = s.updated_by
                        WHERE s.id = 1
                        """)
                .query((rs, rowNum) -> new StoredSettings(
                        rs.getString("base_url"),
                        rs.getString("token_encrypted"),
                        rs.getObject("token_changed_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getString("updated_by_name")
                ))
                .optional();
    }

    @Transactional
    void save(StoredSettings settings, UUID actorProfileId) {
        jdbcClient.sql("DELETE FROM labor_market_settings WHERE id = 1").update();
        jdbcClient.sql("""
                        INSERT INTO labor_market_settings (id, base_url, token_encrypted, token_changed_at, updated_by, updated_at)
                        VALUES (1, :baseUrl, :token, :tokenChangedAt, :updatedBy, :updatedAt)
                        """)
                .param("baseUrl", settings.baseUrl())
                .param("token", settings.tokenEncrypted())
                .param("tokenChangedAt", settings.tokenChangedAt())
                .param("updatedBy", actorProfileId)
                .param("updatedAt", settings.updatedAt())
                .update();
    }

    void delete() {
        jdbcClient.sql("DELETE FROM labor_market_settings WHERE id = 1").update();
    }

    record StoredSettings(
            String baseUrl,
            String tokenEncrypted,
            OffsetDateTime tokenChangedAt,
            OffsetDateTime updatedAt,
            String updatedByName
    ) {
    }
}
