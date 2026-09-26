package ru.rtk.crm.interaction;

import java.util.UUID;

public record WorkflowTemplateStage(UUID id, String name, int order, boolean optional) {
}
