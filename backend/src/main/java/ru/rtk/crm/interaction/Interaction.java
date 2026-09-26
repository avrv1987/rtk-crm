package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.attachment.Attachment;
import ru.rtk.crm.catalog.CatalogReference;

public record Interaction(
        UUID id,
        UUID organizationId,
        String title,
        UUID currentStageId,
        String currentStageName,
        List<InteractionStage> stages,
        List<InteractionStageTransition> transitions,
        List<InteractionTransitionOption> allowedTransitions,
        String nextAction,
        OffsetDateTime nextActionAt,
        List<UUID> contactIds,
        UUID programId,
        CatalogReference program,
        List<UUID> productIds,
        List<ProductAgreement> productAgreements,
        List<Attachment> attachments,
        OffsetDateTime lastContactAt,
        int version,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
