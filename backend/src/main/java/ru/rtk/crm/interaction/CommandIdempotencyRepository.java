package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class CommandIdempotencyRepository {
    private final JdbcClient jdbcClient;
    private final DataSource dataSource;

    public CommandIdempotencyRepository(JdbcClient jdbcClient, DataSource dataSource) {
        this.jdbcClient = jdbcClient;
        this.dataSource = dataSource;
    }

    public boolean reserve(
            UUID commandId,
            UUID actorProfileId,
            CommandOperation operation,
            String idempotencyKey,
            String requestFingerprint,
            OffsetDateTime createdAt
    ) {
        if (isH2()) {
            return reserveForH2(commandId, actorProfileId, operation, idempotencyKey, requestFingerprint, createdAt);
        }
        return jdbcClient.sql("""
                INSERT INTO command_idempotency_records (
                    id, actor_profile_id, operation, idempotency_key, request_fingerprint, created_at
                ) VALUES (
                    :id, :actorProfileId, :operation, :idempotencyKey, :requestFingerprint, :createdAt
                ) ON CONFLICT (actor_profile_id, operation, idempotency_key) DO NOTHING
                """)
                .param("id", commandId)
                .param("actorProfileId", actorProfileId)
                .param("operation", operation.name())
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", requestFingerprint)
                .param("createdAt", createdAt)
                .update() == 1;
    }

    private boolean reserveForH2(
            UUID commandId,
            UUID actorProfileId,
            CommandOperation operation,
            String idempotencyKey,
            String requestFingerprint,
            OffsetDateTime createdAt
    ) {
        if (find(actorProfileId, operation, idempotencyKey).isPresent()) {
            return false;
        }
        try {
            return jdbcClient.sql("""
                    INSERT INTO command_idempotency_records (
                        id, actor_profile_id, operation, idempotency_key, request_fingerprint, created_at
                    )
                    SELECT :id, :actorProfileId, :operation, :idempotencyKey, :requestFingerprint, :createdAt
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM command_idempotency_records
                        WHERE actor_profile_id = :actorProfileId
                          AND operation = :operation
                          AND idempotency_key = :idempotencyKey
                    )
                    """)
                    .param("id", commandId)
                    .param("actorProfileId", actorProfileId)
                    .param("operation", operation.name())
                    .param("idempotencyKey", idempotencyKey)
                    .param("requestFingerprint", requestFingerprint)
                    .param("createdAt", createdAt)
                    .update() == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public Optional<CommandRecord> find(UUID actorProfileId, CommandOperation operation, String idempotencyKey) {
        return jdbcClient.sql("""
                SELECT id, request_fingerprint, result_json
                FROM command_idempotency_records
                WHERE actor_profile_id = :actorProfileId
                  AND operation = :operation
                  AND idempotency_key = :idempotencyKey
                """)
                .param("actorProfileId", actorProfileId)
                .param("operation", operation.name())
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> new CommandRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("request_fingerprint"),
                        resultSet.getString("result_json")
                ))
                .optional();
    }

    public void complete(UUID commandId, String resultJson) {
        int updated = jdbcClient.sql("""
                UPDATE command_idempotency_records
                SET result_json = :resultJson
                WHERE id = :commandId
                """)
                .param("commandId", commandId)
                .param("resultJson", resultJson)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Command idempotency record was not reserved");
        }
    }

    public record CommandRecord(UUID id, String requestFingerprint, String resultJson) {
    }

    private boolean isH2() {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2");
        } catch (SQLException exception) {
            throw new IllegalStateException("Database product could not be resolved", exception);
        }
    }
}
