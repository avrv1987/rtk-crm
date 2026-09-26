package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Organization(
        UUID id,
        String name,
        OrganizationType type,
        UUID teamId,
        UUID ownerManagerId,
        int version,
        OffsetDateTime updatedAt,
        String ownerManagerName,
        String teamName,
        boolean requiresAssignment
) {
}
