package ru.rtk.crm.attachment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository {
    private final JdbcClient jdbcClient;

    public AttachmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(
            UUID id,
            UUID interactionId,
            UUID stageId,
            String originalName,
            String mediaType,
            long sizeBytes,
            UUID storageKey,
            String checksum,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {
        jdbcClient.sql("""
                INSERT INTO attachments (
                    id, interaction_id, stage_id, event_id, original_name, media_type, size_bytes,
                    storage_key, checksum, status, created_by, created_at, updated_at
                ) VALUES (
                    :id, :interactionId, :stageId, NULL, :originalName, :mediaType, :sizeBytes,
                    :storageKey, :checksum, :status, :createdBy, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("interactionId", interactionId)
                .param("stageId", stageId)
                .param("originalName", originalName)
                .param("mediaType", mediaType)
                .param("sizeBytes", sizeBytes)
                .param("storageKey", storageKey)
                .param("checksum", checksum)
                .param("status", AttachmentStatus.QUARANTINE.name())
                .param("createdBy", createdBy)
                .param("createdAt", createdAt)
                .param("updatedAt", createdAt)
                .update();
    }

    public Optional<AttachmentRow> findById(UUID attachmentId) {
        return jdbcClient.sql("""
                SELECT id, interaction_id, stage_id, event_id, original_name, media_type, size_bytes,
                       storage_key, checksum, status, created_at
                FROM attachments
                WHERE id = :attachmentId
                """)
                .param("attachmentId", attachmentId)
                .query(this::mapRow)
                .optional();
    }

    public List<Attachment> findByInteractionId(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT id, interaction_id, stage_id, event_id, original_name, media_type, size_bytes,
                       storage_key, checksum, status, created_at
                FROM attachments
                WHERE interaction_id = :interactionId
                ORDER BY created_at ASC, id ASC
                """)
                .param("interactionId", interactionId)
                .query(this::mapRow)
                .list()
                .stream()
                .map(AttachmentRow::attachment)
                .toList();
    }

    public void updateStatus(UUID attachmentId, AttachmentStatus status, OffsetDateTime updatedAt) {
        int updated = jdbcClient.sql("""
                UPDATE attachments
                SET status = :status, updated_at = :updatedAt
                WHERE id = :attachmentId
                """)
                .param("attachmentId", attachmentId)
                .param("status", status.name())
                .param("updatedAt", updatedAt)
                .update();
        if (updated != 1) {
            throw new AttachmentNotFoundException();
        }
    }

    public void bindCleanUnbound(
            UUID interactionId,
            UUID stageId,
            UUID eventId,
            List<UUID> attachmentIds,
            OffsetDateTime updatedAt
    ) {
        for (UUID attachmentId : attachmentIds) {
            int updated = jdbcClient.sql("""
                    UPDATE attachments
                    SET event_id = :eventId, updated_at = :updatedAt
                    WHERE id = :attachmentId
                      AND interaction_id = :interactionId
                      AND stage_id = :stageId
                      AND event_id IS NULL
                      AND status = :status
                    """)
                    .param("attachmentId", attachmentId)
                    .param("interactionId", interactionId)
                    .param("stageId", stageId)
                    .param("eventId", eventId)
                    .param("status", AttachmentStatus.CLEAN.name())
                    .param("updatedAt", updatedAt)
                    .update();
            if (updated != 1) {
                throw new AttachmentBindingException();
            }
        }
    }

    private AttachmentRow mapRow(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new AttachmentRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getObject("stage_id", UUID.class),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("original_name"),
                resultSet.getString("media_type"),
                resultSet.getLong("size_bytes"),
                resultSet.getObject("storage_key", UUID.class),
                resultSet.getString("checksum"),
                AttachmentStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("created_at", OffsetDateTime.class)
        );
    }

    record AttachmentRow(
            UUID id,
            UUID interactionId,
            UUID stageId,
            UUID eventId,
            String originalName,
            String mediaType,
            long sizeBytes,
            UUID storageKey,
            String checksum,
            AttachmentStatus status,
            OffsetDateTime createdAt
    ) {
        Attachment attachment() {
            return new Attachment(
                    id,
                    interactionId,
                    stageId,
                    eventId,
                    originalName,
                    mediaType,
                    sizeBytes,
                    status,
                    createdAt
            );
        }
    }
}
