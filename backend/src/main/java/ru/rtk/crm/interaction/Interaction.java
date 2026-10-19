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
        List<InteractionStageCompletion> stageCompletions,
        OffsetDateTime lastContactAt,
        int version,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        InteractionMarks marks,
        boolean nextStepPartnerVisible,
        InteractionRoute route,
        InteractionKindModels.InteractionKindRef kind,
        List<StageDocument> documentChecklist
) {
    public Interaction {
        documentChecklist = documentChecklist == null ? List.of() : List.copyOf(documentChecklist);
    }

    public Interaction(
            UUID id, UUID organizationId, String title, UUID currentStageId, String currentStageName,
            List<InteractionStage> stages, List<InteractionStageTransition> transitions,
            List<InteractionTransitionOption> allowedTransitions, String nextAction, OffsetDateTime nextActionAt,
            List<UUID> contactIds, UUID programId, CatalogReference program, List<UUID> productIds,
            List<ProductAgreement> productAgreements, List<Attachment> attachments,
            List<InteractionStageCompletion> stageCompletions, OffsetDateTime lastContactAt, int version, UUID createdBy,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, InteractionMarks marks, boolean nextStepPartnerVisible,
            InteractionRoute route, InteractionKindModels.InteractionKindRef kind
    ) {
        this(id, organizationId, title, currentStageId, currentStageName, stages, transitions, allowedTransitions, nextAction,
                nextActionAt, contactIds, programId, program, productIds, productAgreements, attachments, stageCompletions,
                lastContactAt, version, createdBy, createdAt, updatedAt, marks, nextStepPartnerVisible, route, kind, List.of());
    }

    /** Строка чек-листа документов текущего этапа: обязательный вид и приложен ли к этапу документ этого вида. */
    public record StageDocument(String code, String name, boolean attached) {
    }

    public List<String> missingDocumentNames() {
        return documentChecklist.stream().filter(document -> !document.attached()).map(StageDocument::name).toList();
    }
}
