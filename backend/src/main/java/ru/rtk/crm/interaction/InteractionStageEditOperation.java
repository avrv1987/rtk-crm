package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record InteractionStageEditOperation(
        Type type,
        UUID id,
        UUID afterId,
        String name,
        Boolean optional,
        Boolean requiresAgreement,
        StageLmsRole lmsRole,
        List<String> documentKinds
) {
    public InteractionStageEditOperation(Type type, UUID id, UUID afterId, String name, Boolean optional) {
        this(type, id, afterId, name, optional, null, null, null);
    }

    public enum Type {
        RENAME,
        ADD_AFTER,
        MOVE_AFTER,
        DELETE,
        SET_OPTIONAL

    }
}
