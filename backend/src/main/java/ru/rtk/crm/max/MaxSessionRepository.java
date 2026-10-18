package ru.rtk.crm.max;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@MaxEnabled
public class MaxSessionRepository {
    private final JdbcClient jdbcClient;

    public MaxSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(String tokenHash, UUID profileId, long maxUserId, int accessRevision, OffsetDateTime now, OffsetDateTime expiresAt) {
        jdbcClient.sql("""
                INSERT INTO max_sessions (token_hash, profile_id, max_user_id, access_revision, created_at, last_seen_at, expires_at)
                VALUES (:tokenHash, :profileId, :maxUserId, :accessRevision, :now, :now, :expiresAt)
                """)
                .param("tokenHash", tokenHash)
                .param("profileId", profileId)
                .param("maxUserId", maxUserId)
                .param("accessRevision", accessRevision)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .update();
    }

    public Optional<Session> find(String tokenHash) {
        return jdbcClient.sql("""
                SELECT token_hash, profile_id, max_user_id, access_revision, created_at, last_seen_at, expires_at
                FROM max_sessions
                WHERE token_hash = :tokenHash
                """)
                .param("tokenHash", tokenHash)
                .query(Session.class)
                .optional();
    }

    public void touch(String tokenHash, OffsetDateTime now) {
        jdbcClient.sql("UPDATE max_sessions SET last_seen_at = :now WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .param("now", now)
                .update();
    }

    public void delete(String tokenHash) {
        jdbcClient.sql("DELETE FROM max_sessions WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .update();
    }

    public int deleteByProfile(UUID profileId) {
        return jdbcClient.sql("DELETE FROM max_sessions WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .update();
    }

    public int deleteStale(OffsetDateTime now, OffsetDateTime idleBefore) {
        return jdbcClient.sql("DELETE FROM max_sessions WHERE expires_at < :now OR last_seen_at < :idleBefore")
                .param("now", now)
                .param("idleBefore", idleBefore)
                .update();
    }

    public record Session(
            String tokenHash,
            UUID profileId,
            long maxUserId,
            int accessRevision,
            OffsetDateTime createdAt,
            OffsetDateTime lastSeenAt,
            OffsetDateTime expiresAt
    ) {
    }
}
