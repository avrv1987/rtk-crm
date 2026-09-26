package ru.rtk.crm.interaction;

import java.util.UUID;

public record InteractionStageTransition(UUID fromStageId, UUID toStageId, boolean commentRequired) {
}
