package ru.rtk.crm.interaction;

import java.time.LocalDate;
import java.util.UUID;

public record InteractionIssueRequest(
        Integer version,
        InteractionIssueKind kind,
        String description,
        InteractionRiskLevel riskLevel,
        UUID responsibleId,
        LocalDate dueOn
) {
}
