package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InteractionEvent(
        UUID id,
        InteractionEventType type,
        UUID commandId,
        UUID stageId,
        String stageNameSnapshot,
        UUID fromStageId,
        String fromStageNameSnapshot,
        UUID toStageId,
        String toStageNameSnapshot,
        String comment,
        InteractionNextStep nextStep,
        UUID actorProfileId,
        String actorDisplayName,
        UUID ownerManagerIdSnapshot,
        int version,
        OffsetDateTime occurredAt
) {
}
