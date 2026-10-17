package ru.rtk.crm.access;

import java.util.List;
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
                SELECT subject, active, pending_activation, idp_enabled, role_reconcile_required
                FROM crm_user_profiles
                WHERE id = :profileId
                """)
                .param("profileId", profileId)
                .query((resultSet, rowNumber) -> new Account(
                        resultSet.getString("subject"),
                        resultSet.getBoolean("active"),
                        resultSet.getBoolean("pending_activation"),
                        resultSet.getBoolean("idp_enabled"),
                        resultSet.getBoolean("role_reconcile_required")
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

    public List<RoleAccount> findRolesNotInKeycloak() {
        return jdbcClient.sql("""
                SELECT id, subject, role
                FROM crm_user_profiles
                WHERE role_synced_at IS NULL AND pending_activation = FALSE AND anonymized_at IS NULL
                ORDER BY id
                """)
                .query((resultSet, rowNumber) -> new RoleAccount(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("subject"),
                        UserRole.valueOf(resultSet.getString("role"))
                ))
                .list();
    }

    public record RoleAccount(UUID id, String subject, UserRole role) {
    }

    public record Account(
            String subject, boolean active, boolean pendingActivation, boolean idpEnabled, boolean roleReconcileRequired
    ) {
        boolean synced() {
            return pendingActivation || active == idpEnabled;
        }
    }
}
