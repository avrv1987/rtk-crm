package ru.rtk.crm.attachment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Attachment(
        UUID id,
        UUID interactionId,
        UUID stageId,
        UUID eventId,
        String originalName,
        String mediaType,
        long sizeBytes,
        AttachmentStatus status,
        OffsetDateTime createdAt
) {
}
