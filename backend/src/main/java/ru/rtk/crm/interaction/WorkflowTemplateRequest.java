package ru.rtk.crm.interaction;

import java.util.List;

public record WorkflowTemplateRequest(
        String name,
        List<WorkflowStageInput> stages,
        List<WorkflowTransitionInput> transitions,
        Integer version
) {
}
