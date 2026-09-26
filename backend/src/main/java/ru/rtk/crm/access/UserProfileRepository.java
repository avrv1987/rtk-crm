package ru.rtk.crm.access;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserProfileRepository {
    private final JdbcClient jdbcClient;

    public UserProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CrmProfile> findActiveByIdentity(String issuer, String subject) {
        return jdbcClient.sql("""
                SELECT id, role, team_id, access_revision
                FROM crm_user_profiles
                WHERE issuer = :issuer AND subject = :subject AND active = TRUE
                """)
                .param("issuer", issuer)
                .param("subject", subject)
                .query(CrmProfile.class)
                .optional();
    }

    public boolean isPendingActivation(String issuer, String subject) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM crm_user_profiles
                WHERE issuer = :issuer AND subject = :subject AND pending_activation = TRUE
                """)
                .param("issuer", issuer)
                .param("subject", subject)
                .query(Long.class)
                .single() > 0;
    }

    public boolean insertPendingIfAbsent(UUID id, String issuer, String subject, String displayName) {
        return jdbcClient.sql("""
                INSERT INTO crm_user_profiles (id, issuer, subject, display_name, role, team_id, active, pending_activation)
                VALUES (:id, :issuer, :subject, :displayName, 'USER', NULL, FALSE, TRUE)
                ON CONFLICT DO NOTHING
                """)
                .param("id", id)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("displayName", displayName)
                .update() == 1;
    }

    public boolean activatePending(String issuer, String subject, String displayName, UserRole role, UUID teamId) {
        return jdbcClient.sql("""
                UPDATE crm_user_profiles
                SET display_name = :displayName,
                    role = :role,
                    team_id = :teamId,
                    active = TRUE,
                    pending_activation = FALSE,
                    access_revision = access_revision + 1,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE issuer = :issuer AND subject = :subject AND pending_activation = TRUE
                """)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("displayName", displayName)
                .param("role", role.name())
                .param("teamId", teamId)
                .update() == 1;
    }

    public Optional<String> findTeamName(UUID teamId) {
        return jdbcClient.sql("SELECT name FROM teams WHERE id = :teamId")
                .param("teamId", teamId)
                .query(String.class)
                .optional();
    }
}
