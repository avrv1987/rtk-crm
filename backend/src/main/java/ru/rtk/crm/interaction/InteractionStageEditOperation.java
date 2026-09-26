package ru.rtk.crm.interaction;

import java.util.UUID;

public record InteractionStageEditOperation(
        Type type,
        UUID id,
        UUID afterId,
        String name,
        Boolean optional
) {
    public enum Type {
        RENAME,
        ADD_AFTER,
        MOVE_AFTER,
        DELETE
    }
}
