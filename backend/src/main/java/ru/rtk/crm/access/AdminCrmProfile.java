package ru.rtk.crm.access;

import java.util.UUID;

public record AdminCrmProfile(
        UUID id,
        String displayName,
        UserRole role,
        UUID teamId,
        String teamName,
        boolean active,
        boolean pendingActivation,
        int accessRevision,
        int version
) {
}
