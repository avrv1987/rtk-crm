package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record WorkflowTemplateStage(
        UUID id,
        String name,
        int order,
        boolean optional,
        boolean requiresAgreement,
        StageLmsRole lmsRole,
        List<String> documentKinds
) {
    public WorkflowTemplateStage {
        documentKinds = documentKinds == null ? List.of() : List.copyOf(documentKinds);
    }

    public WorkflowTemplateStage(UUID id, String name, int order, boolean optional) {
        this(id, name, order, optional, false, null, List.of());
    }
}
