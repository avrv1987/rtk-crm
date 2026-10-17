package ru.rtk.crm.interaction;

import java.util.UUID;

public record WorkflowStageMapInput(UUID fromStageId, UUID toStageId) {
}
