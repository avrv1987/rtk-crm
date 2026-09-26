package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InteractionSummary(
        UUID id,
        UUID organizationId,
        String title,
        UUID currentStageId,
        String currentStageName,
        String nextAction,
        OffsetDateTime nextActionAt,
        List<UUID> contactIds,
        UUID programId,
        List<UUID> productIds,
        OffsetDateTime lastContactAt,
        int version,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String organizationName,
        String programName,
        String ownerManagerName,
        InteractionMarks marks,
        String lastEventType,
        OffsetDateTime lastEventAt,
        OffsetDateTime stageEnteredAt,
        String deputyManagerName,
        LocalDate deputyEndsOn
) {
}
