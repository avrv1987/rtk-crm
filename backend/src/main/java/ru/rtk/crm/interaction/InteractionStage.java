package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record InteractionStage(
        UUID id,
        String name,
        int order,
        boolean optional,
        boolean requiresAgreement,
        StageLmsRole lmsRole,
        List<String> documentKinds
) {
    public InteractionStage {
        documentKinds = documentKinds == null ? List.of() : List.copyOf(documentKinds);
    }

    public InteractionStage(UUID id, String name, int order, boolean optional) {
        this(id, name, order, optional, false, null, List.of());
    }

    InteractionStage withName(String value) {
        return new InteractionStage(id, value, order, optional, requiresAgreement, lmsRole, documentKinds);
    }

    InteractionStage withOrder(int value) {
        return new InteractionStage(id, name, value, optional, requiresAgreement, lmsRole, documentKinds);
    }

    InteractionStage withDocumentKinds(java.util.List<String> value) {
        return new InteractionStage(id, name, order, optional, requiresAgreement, lmsRole, value);
    }

    InteractionStage withOptional(boolean value) {
        return new InteractionStage(id, name, order, value, requiresAgreement, lmsRole, documentKinds);
    }
}
