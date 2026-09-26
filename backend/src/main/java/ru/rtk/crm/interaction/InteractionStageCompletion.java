package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InteractionStageCompletion(
        UUID stageId,
        LocalDate completedOn,
        String comment,
        UUID eventId,
        String actorDisplayName,
        OffsetDateTime markedAt
) {
}
