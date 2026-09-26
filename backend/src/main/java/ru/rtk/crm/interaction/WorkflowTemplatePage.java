package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record WorkflowTemplatePage(
        List<WorkflowTemplate> items,
        int page,
        int size,
        long total,
        UUID teamDefaultTemplateId
) {
    WorkflowTemplatePage withTeamDefault(UUID templateId) {
        return new WorkflowTemplatePage(items, page, size, total, templateId);
    }
}
