package ru.rtk.crm.attachment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository {
    private static final String SELECT = """
            SELECT id, interaction_id, stage_id, event_id, original_name, media_type, size_bytes,
                   storage_key, checksum, status, kind, revision, replaces_id, created_by, version, created_at
            FROM attachments
            """;

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
            AttachmentKind kind,
            int revision,
            UUID replacesId,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {
        jdbcClient.sql("""
                INSERT INTO attachments (
                    id, interaction_id, stage_id, event_id, original_name, media_type, size_bytes,
                    storage_key, checksum, status, kind, revision, replaces_id, created_by, created_at, updated_at
                ) VALUES (
                    :id, :interactionId, :stageId, NULL, :originalName, :mediaType, :sizeBytes,
                    :storageKey, :checksum, :status, :kind, :revision, :replacesId, :createdBy, :createdAt, :updatedAt
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
                .param("kind", kind.name())
                .param("revision", revision)
                .param("replacesId", replacesId)
                .param("createdBy", createdBy)
                .param("createdAt", createdAt)
                .param("updatedAt", createdAt)
                .update();
    }

    public Optional<AttachmentRow> findById(UUID attachmentId) {
        return jdbcClient.sql(SELECT + "WHERE id = :attachmentId AND deleted_at IS NULL")
                .param("attachmentId", attachmentId)
                .query(this::mapRow)
                .optional();
    }

    public List<Attachment> findByInteractionId(UUID interactionId) {
        return jdbcClient.sql(SELECT + """
                WHERE interaction_id = :interactionId AND deleted_at IS NULL
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

    public boolean updateKind(UUID attachmentId, AttachmentKind kind, int expectedVersion, OffsetDateTime updatedAt) {
        return jdbcClient.sql("""
                UPDATE attachments
                SET kind = :kind, version = version + 1, updated_at = :updatedAt
                WHERE id = :attachmentId AND version = :expectedVersion AND deleted_at IS NULL
                """)
                .param("attachmentId", attachmentId)
                .param("kind", kind.name())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", updatedAt)
                .update() == 1;
    }

    public void markDeleted(UUID attachmentId, UUID deletedBy, OffsetDateTime deletedAt) {
        int updated = jdbcClient.sql("""
                UPDATE attachments
                SET deleted_at = :deletedAt, deleted_by = :deletedBy, version = version + 1, updated_at = :deletedAt
                WHERE id = :attachmentId AND deleted_at IS NULL
                """)
                .param("attachmentId", attachmentId)
                .param("deletedBy", deletedBy)
                .param("deletedAt", deletedAt)
                .update();
        if (updated != 1) {
            throw new AttachmentNotFoundException();
        }
    }

    public boolean hasLiveReplacement(UUID attachmentId) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM attachments
                WHERE replaces_id = :attachmentId AND deleted_at IS NULL AND status IN ('QUARANTINE', 'CLEAN')
                """)
                .param("attachmentId", attachmentId)
                .query(Long.class)
                .single() > 0;
    }

    public Optional<String> findAgreementUsage(UUID attachmentId) {
        return jdbcClient.sql("""
                SELECT product.name
                FROM product_agreements agreement
                JOIN products product ON product.id = agreement.product_id
                WHERE agreement.scan_attachment_id = :attachmentId
                   OR agreement.id IN (SELECT agreement_id FROM product_transfers WHERE attachment_id = :attachmentId)
                ORDER BY product.name
                """)
                .param("attachmentId", attachmentId)
                .query(String.class)
                .list()
                .stream()
                .findFirst();
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
                      AND deleted_at IS NULL
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
                AttachmentKind.valueOf(resultSet.getString("kind")),
                resultSet.getInt("revision"),
                resultSet.getObject("replaces_id", UUID.class),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getInt("version"),
                resultSet.getObject("created_at", OffsetDateTime.class)
        );
    }

    public record AttachmentRow(
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
            AttachmentKind kind,
            int revision,
            UUID replacesId,
            UUID createdBy,
            int version,
            OffsetDateTime createdAt
    ) {
        public Attachment attachment() {
            return new Attachment(
                    id,
                    interactionId,
                    stageId,
                    eventId,
                    originalName,
                    mediaType,
                    sizeBytes,
                    status,
                    kind,
                    revision,
                    replacesId,
                    createdBy,
                    version,
                    createdAt
            );
        }
    }
}
