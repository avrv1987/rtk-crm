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
        OffsetDateTime occurredAt,
        // Событие записано по плану ИИ-агента, который подтвердил сотрудник (ключ команды agent-plan:<id>).
        boolean preparedByAgent,
        // Ссылка на документ, договор или карточку другого цикла у событий из соседних таблиц; у остальных null.
        InteractionEventLink link,
        // Событие перенесено импортом истории: источник (файл и строка) и автор из файла — сотрудник или «импорт».
        // actorDisplayName у такого события — администратор, выполнивший загрузку.
        String importSource,
        String importAuthor
) {
    public InteractionEvent(UUID id, InteractionEventType type, UUID commandId, UUID stageId, String stageNameSnapshot,
                            UUID fromStageId, String fromStageNameSnapshot, UUID toStageId, String toStageNameSnapshot,
                            String comment, InteractionNextStep nextStep, UUID actorProfileId, String actorDisplayName,
                            UUID ownerManagerIdSnapshot, int version, OffsetDateTime occurredAt, boolean preparedByAgent,
                            InteractionEventLink link) {
        this(id, type, commandId, stageId, stageNameSnapshot, fromStageId, fromStageNameSnapshot, toStageId, toStageNameSnapshot,
                comment, nextStep, actorProfileId, actorDisplayName, ownerManagerIdSnapshot, version, occurredAt, preparedByAgent, link,
                null, null);
    }

    public InteractionEvent(UUID id, InteractionEventType type, UUID commandId, UUID stageId, String stageNameSnapshot,
                            UUID fromStageId, String fromStageNameSnapshot, UUID toStageId, String toStageNameSnapshot,
                            String comment, InteractionNextStep nextStep, UUID actorProfileId, String actorDisplayName,
                            UUID ownerManagerIdSnapshot, int version, OffsetDateTime occurredAt, boolean preparedByAgent) {
        this(id, type, commandId, stageId, stageNameSnapshot, fromStageId, fromStageNameSnapshot, toStageId, toStageNameSnapshot,
                comment, nextStep, actorProfileId, actorDisplayName, ownerManagerIdSnapshot, version, occurredAt, preparedByAgent, null, null, null);
    }
}
