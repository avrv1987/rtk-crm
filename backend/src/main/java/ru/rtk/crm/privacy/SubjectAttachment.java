package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubjectAttachment(
        UUID id,
        UUID interactionId,
        String interactionTitle,
        String organizationName,
        String fileName,
        long sizeBytes,
        OffsetDateTime createdAt
) {
}
