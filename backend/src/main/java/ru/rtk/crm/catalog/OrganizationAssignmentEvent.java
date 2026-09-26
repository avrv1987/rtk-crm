package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrganizationAssignmentEvent(
        UUID id,
        UUID organizationId,
        UUID commandId,
        UUID previousOwnerManagerId,
        String previousOwnerManagerDisplayName,
        UUID ownerManagerId,
        String newOwnerManagerDisplayName,
        UUID actorProfileId,
        String actorDisplayName,
        String requestId,
        OrganizationAssignmentReason reason,
        String handoverNote,
        int version,
        OffsetDateTime occurredAt
) {
}
