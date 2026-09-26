package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InteractionTransitionRequest(
        @NotNull @Min(0) Integer version,
        @NotNull UUID toStageId,
        @Size(max = 4_000) String comment,
        @Size(max = 100) List<UUID> attachmentIds,
        InteractionNextStep nextStep
) {
    public InteractionTransitionRequest(Integer version, UUID toStageId, String comment) {
        this(version, toStageId, comment, List.of(), null);
    }
}
