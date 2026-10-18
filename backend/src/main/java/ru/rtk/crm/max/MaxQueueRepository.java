package ru.rtk.crm.max;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@MaxEnabled
public class MaxQueueRepository {
    static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbcClient;

    public MaxQueueRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Повторная доставка того же события даёт тот же ключ: строка не дублируется, а вебхук всё равно отвечает 200,
    // иначе MAX продолжил бы повторять.
    public boolean insertInbox(String eventKey, String updateType, String payload, OffsetDateTime now) {
        return jdbcClient.sql("""
                INSERT INTO max_inbox (id, event_key, update_type, payload, status, attempts, received_at)
                VALUES (:id, :eventKey, :updateType, :payload, 'RECEIVED', 0, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("eventKey", eventKey)
                .param("updateType", updateType)
                .param("payload", payload)
                .param("now", now)
                .update() == 1;
    }

    public List<InboxRow> findReceived(int limit) {
        return jdbcClient.sql("""
                SELECT id, event_key, update_type, payload, attempts
                FROM max_inbox
                WHERE status = 'RECEIVED'
                ORDER BY received_at, id
                LIMIT :limit
                """)
                .param("limit", limit)
                .query(InboxRow.class)
                .list();
    }

    public void markInboxDone(UUID id, OffsetDateTime now) {
        jdbcClient.sql("UPDATE max_inbox SET status = 'DONE', processed_at = :now WHERE id = :id")
                .param("id", id)
                .param("now", now)
                .update();
    }

    public void markInboxFailed(UUID id, String error, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE max_inbox
                SET attempts = attempts + 1, last_error = :error, processed_at = :now,
                    status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'ERROR' ELSE 'RECEIVED' END
                WHERE id = :id
                """)
                .param("id", id)
                .param("error", MaxSettingsRepository.truncate(error))
                .param("now", now)
                .param("maxAttempts", MAX_ATTEMPTS)
                .update();
    }

    public boolean enqueue(
            String eventKey,
            UUID profileId,
            long maxUserId,
            MaxMessageKind kind,
            UUID organizationId,
            String body,
            OffsetDateTime now,
            OffsetDateTime expiresAt
    ) {
        return jdbcClient.sql("""
                INSERT INTO max_outbox (id, event_key, profile_id, max_user_id, kind, organization_id, body, status,
                                        attempts, next_attempt_at, expires_at, created_at)
                VALUES (:id, :eventKey, :profileId, :maxUserId, :kind, :organizationId, :body, 'QUEUED',
                        0, :now, :expiresAt, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("eventKey", eventKey.length() > 200 ? MaxCodes.sha256(eventKey) : eventKey)
                .param("profileId", profileId)
                .param("maxUserId", maxUserId)
                .param("kind", kind.name())
                .param("organizationId", organizationId)
                .param("body", body)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .update() == 1;
    }

    public List<OutboxRow> findDue(OffsetDateTime now, int limit) {
        return jdbcClient.sql("""
                SELECT id, profile_id, max_user_id, kind, organization_id, body, attempts, expires_at
                FROM max_outbox
                WHERE status = 'QUEUED' AND next_attempt_at <= :now
                ORDER BY next_attempt_at, created_at, id
                LIMIT :limit
                """)
                .param("now", now)
                .param("limit", limit)
                .query((rs, row) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("profile_id", UUID.class),
                        rs.getLong("max_user_id"),
                        MaxMessageKind.valueOf(rs.getString("kind")),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("body"),
                        rs.getInt("attempts"),
                        rs.getObject("expires_at", OffsetDateTime.class)
                ))
                .list();
    }

    public void markSent(UUID id, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE max_outbox
                SET status = 'SENT', sent_at = :now, attempts = attempts + 1, last_error = CAST(NULL AS VARCHAR(500))
                WHERE id = :id
                """)
                .param("id", id)
                .param("now", now)
                .update();
    }

    public void markDropped(UUID id, String reason) {
        jdbcClient.sql("UPDATE max_outbox SET status = 'DROPPED', last_error = :reason WHERE id = :id")
                .param("id", id)
                .param("reason", MaxSettingsRepository.truncate(reason))
                .update();
    }

    public void markError(UUID id, String error) {
        jdbcClient.sql("UPDATE max_outbox SET status = 'ERROR', attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("id", id)
                .param("error", MaxSettingsRepository.truncate(error))
                .update();
    }

    public void markRetry(UUID id, OffsetDateTime nextAttemptAt, String error) {
        jdbcClient.sql("""
                UPDATE max_outbox
                SET attempts = attempts + 1, next_attempt_at = :next, last_error = :error,
                    status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'ERROR' ELSE 'QUEUED' END
                WHERE id = :id
                """)
                .param("id", id)
                .param("next", nextAttemptAt)
                .param("error", MaxSettingsRepository.truncate(error))
                .param("maxAttempts", MAX_ATTEMPTS)
                .update();
    }

    // Пользователь остановил бота: всё, что ждало отправки, сбрасывается, чтобы после возобновления не пришёл архив.
    public int dropQueuedFor(long maxUserId, String reason) {
        return jdbcClient.sql("UPDATE max_outbox SET status = 'DROPPED', last_error = :reason WHERE max_user_id = :maxUserId AND status = 'QUEUED'")
                .param("maxUserId", maxUserId)
                .param("reason", reason)
                .update();
    }

    public int dropQueuedForProfile(UUID profileId, String reason) {
        return jdbcClient.sql("UPDATE max_outbox SET status = 'DROPPED', last_error = :reason WHERE profile_id = :profileId AND status = 'QUEUED'")
                .param("profileId", profileId)
                .param("reason", reason)
                .update();
    }

    // В очередь возвращаются только ещё не устаревшие ошибки: просроченное отправщик всё равно сбросит.
    public int requeueErrors(OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE max_outbox
                SET status = 'QUEUED', attempts = 0, next_attempt_at = :now
                WHERE status = 'ERROR' AND expires_at > :now
                """)
                .param("now", now)
                .update();
    }

    public QueueCounts counts(OffsetDateTime since) {
        return jdbcClient.sql("""
                SELECT
                    (SELECT COUNT(*) FROM max_outbox WHERE status = 'QUEUED') AS queued,
                    (SELECT COUNT(*) FROM max_outbox WHERE status = 'ERROR') AS errors,
                    (SELECT COUNT(*) FROM max_outbox WHERE status = 'SENT' AND sent_at >= :since) AS sent,
                    (SELECT COUNT(*) FROM max_outbox WHERE status = 'DROPPED' AND created_at >= :since) AS dropped,
                    (SELECT COUNT(*) FROM max_inbox WHERE status = 'RECEIVED') AS inbox_waiting,
                    (SELECT COUNT(*) FROM max_inbox WHERE status = 'ERROR') AS inbox_errors
                """)
                .param("since", since)
                .query(QueueCounts.class)
                .single();
    }

    public int deleteInboxBefore(OffsetDateTime cutoff) {
        return jdbcClient.sql("DELETE FROM max_inbox WHERE received_at < :cutoff AND status <> 'RECEIVED'")
                .param("cutoff", cutoff)
                .update();
    }

    public int deleteOutboxBefore(OffsetDateTime cutoff) {
        return jdbcClient.sql("DELETE FROM max_outbox WHERE created_at < :cutoff AND status <> 'QUEUED'")
                .param("cutoff", cutoff)
                .update();
    }

    public record InboxRow(UUID id, String eventKey, String updateType, String payload, int attempts) {
    }

    public record OutboxRow(
            UUID id,
            UUID profileId,
            long maxUserId,
            MaxMessageKind kind,
            UUID organizationId,
            String body,
            int attempts,
            OffsetDateTime expiresAt
    ) {
    }

    public record QueueCounts(long queued, long errors, long sent, long dropped, long inboxWaiting, long inboxErrors) {
    }
}
