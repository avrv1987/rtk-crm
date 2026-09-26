package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InteractionStageCompletionRequest(
        @NotNull @Min(0) Integer version,
        @NotNull UUID stageId,
        @NotNull LocalDate completedOn,
        @Size(max = 4_000) String comment,
        @Size(max = 100) List<UUID> attachmentIds
) {
}
