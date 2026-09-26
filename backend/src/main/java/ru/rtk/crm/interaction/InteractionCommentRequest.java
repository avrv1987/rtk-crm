package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InteractionCommentRequest(
        @NotNull @Min(0) Integer version,
        @NotNull UUID stageId,
        @NotBlank @Size(max = 4_000) String text,
        @Size(max = 100) List<UUID> attachmentIds,
        InteractionNextStep nextStep
) {
    public InteractionCommentRequest(Integer version, UUID stageId, String text) {
        this(version, stageId, text, List.of(), null);
    }

    public InteractionCommentRequest(Integer version, UUID stageId, String text, List<UUID> attachmentIds) {
        this(version, stageId, text, attachmentIds, null);
    }
}
