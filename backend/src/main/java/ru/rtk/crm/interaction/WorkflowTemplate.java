package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record WorkflowTemplate(
        UUID id,
        UUID teamId,
        String name,
        List<WorkflowTemplateStage> stages,
        List<WorkflowTemplateTransition> transitions,
        boolean defaultTemplate,
        int version
) {
}
