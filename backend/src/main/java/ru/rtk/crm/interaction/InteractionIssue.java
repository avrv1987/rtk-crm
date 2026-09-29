package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InteractionIssue(
        UUID id,
        UUID interactionId,
        String interactionTitle,
        UUID organizationId,
        String organizationName,
        String ownerManagerName,
        InteractionIssueKind kind,
        String description,
        InteractionRiskLevel riskLevel,
        UUID responsibleId,
        String responsibleName,
        LocalDate dueOn,
        InteractionIssueStatus status,
        String resolution,
        UUID createdBy,
        String createdByName,
        OffsetDateTime createdAt,
        String resolvedByName,
        OffsetDateTime resolvedAt
) {
}
