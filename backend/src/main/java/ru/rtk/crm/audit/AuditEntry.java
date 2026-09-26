package ru.rtk.crm.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditEntry(
        UUID id,
        OffsetDateTime occurredAt,
        AuditCategory category,
        AuditAction action,
        String actionLabel,
        UUID actorProfileId,
        String actorDisplayName,
        String objectType,
        UUID objectId,
        String objectName,
        String details,
        String requestId
) {
}
