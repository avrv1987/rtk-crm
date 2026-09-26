package ru.rtk.crm.access;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountSyncRepository {
    private final JdbcClient jdbcClient;

    public AccountSyncRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Account> findAccount(UUID profileId) {
        return jdbcClient.sql("""
                SELECT subject, active, pending_activation, idp_enabled
                FROM crm_user_profiles
                WHERE id = :profileId
                """)
                .param("profileId", profileId)
                .query((resultSet, rowNumber) -> new Account(
                        resultSet.getString("subject"),
                        resultSet.getBoolean("active"),
                        resultSet.getBoolean("pending_activation"),
                        resultSet.getBoolean("idp_enabled")
                ))
                .optional();
    }

    public boolean markSynced(UUID profileId, boolean enabled) {
        return jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET idp_enabled = :enabled
                WHERE id = :profileId AND active = :enabled AND pending_activation = FALSE
                """)
                .param("profileId", profileId)
                .param("enabled", enabled)
                .update() == 1;
    }

    public record Account(String subject, boolean active, boolean pendingActivation, boolean idpEnabled) {
        boolean synced() {
            return pendingActivation || active == idpEnabled;
        }
    }
}
