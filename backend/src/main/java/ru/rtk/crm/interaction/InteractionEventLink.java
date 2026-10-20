package ru.rtk.crm.interaction;

import java.util.UUID;

public record InteractionEventLink(Kind kind, UUID id, UUID organizationId, String label) {
    public enum Kind {
        ATTACHMENT,
        AGREEMENT,
        INTERACTION
    }
}
