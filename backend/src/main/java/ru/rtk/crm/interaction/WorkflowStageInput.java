package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record WorkflowStageInput(
        String name,
        Integer order,
        Boolean optional,
        UUID id,
        Boolean requiresAgreement,
        StageLmsRole lmsRole,
        List<String> documentKinds,
        Integer normDays
) {
    public WorkflowStageInput(String name, Integer order, Boolean optional, UUID id) {
        this(name, order, optional, id, null, null, null, null);
    }

    public WorkflowStageInput(String name, Integer order, Boolean optional, UUID id, Boolean requiresAgreement,
                              StageLmsRole lmsRole, List<String> documentKinds) {
        this(name, order, optional, id, requiresAgreement, lmsRole, documentKinds, null);
    }
}
