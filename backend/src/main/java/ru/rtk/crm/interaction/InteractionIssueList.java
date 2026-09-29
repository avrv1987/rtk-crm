package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record InteractionIssueList(List<InteractionIssue> items, List<ResponsibleOption> responsibleOptions) {
    public record ResponsibleOption(UUID id, String displayName) {
    }
}
