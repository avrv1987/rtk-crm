package ru.rtk.crm.interaction;

import java.util.List;

public record WorkflowTemplatePage(List<WorkflowTemplate> items, int page, int size, long total) {
}
