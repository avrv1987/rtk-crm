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
        String kind,
        String kindName,
        int revision,
        UUID replacesId,
        UUID createdBy,
        int version,
        OffsetDateTime createdAt,
        boolean partnerVisible
) {
}
