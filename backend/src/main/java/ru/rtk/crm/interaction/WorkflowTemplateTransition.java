package ru.rtk.crm.interaction;

import java.util.UUID;

public record WorkflowTemplateTransition(UUID fromStageId, UUID toStageId, boolean commentRequired) {
}
