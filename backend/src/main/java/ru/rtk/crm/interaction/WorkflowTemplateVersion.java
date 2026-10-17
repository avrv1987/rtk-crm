package ru.rtk.crm.interaction;

import java.time.OffsetDateTime;
import java.util.List;

public record WorkflowTemplateVersion(
        int number,
        String name,
        String comment,
        String authorName,
        OffsetDateTime createdAt,
        List<WorkflowTemplateStage> stages,
        List<WorkflowTemplateTransition> transitions,
        Long works
) {
}
