package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrganizationHistoryItem(
        UUID id,
        OrganizationHistoryKind kind,
        OffsetDateTime occurredAt,
        String actorName,
        UUID interactionId,
        String interactionTitle,
        String fromStageName,
        String stageName,
        String description,
        String comment,
        UUID contactId,
        String contactName,
        List<ContactEvent.Change> changes
) {
}
