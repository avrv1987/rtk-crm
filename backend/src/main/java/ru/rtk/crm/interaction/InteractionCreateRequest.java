package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InteractionCreateRequest(
        @NotNull UUID organizationId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 500) String nextAction,
        OffsetDateTime nextActionAt,
        @Size(max = 100) List<@NotNull UUID> contactIds,
        UUID programId,
        @Size(max = 100) List<@NotNull UUID> productIds,
        OffsetDateTime lastContactAt,
        UUID templateId
) {
    public InteractionCreateRequest(
            UUID organizationId,
            String title,
            String nextAction,
            OffsetDateTime nextActionAt,
            List<UUID> contactIds
    ) {
        this(organizationId, title, nextAction, nextActionAt, contactIds, null, List.of(), null, null);
    }

    public InteractionCreateRequest(
            UUID organizationId,
            String title,
            String nextAction,
            OffsetDateTime nextActionAt,
            List<UUID> contactIds,
            UUID programId,
            List<UUID> productIds,
            OffsetDateTime lastContactAt
    ) {
        this(organizationId, title, nextAction, nextActionAt, contactIds, programId, productIds, lastContactAt, null);
    }
}
