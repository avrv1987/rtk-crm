package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CatalogChangeEvent(
        UUID id,
        CatalogEntityType entityType,
        UUID entityId,
        CatalogChangeAction action,
        String entityName,
        String changes,
        String actorDisplayName,
        String requestId,
        OffsetDateTime occurredAt
) {
}
