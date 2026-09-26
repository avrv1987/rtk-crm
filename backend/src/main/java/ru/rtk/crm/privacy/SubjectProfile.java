package ru.rtk.crm.privacy;

import java.util.UUID;

public record SubjectProfile(
        UUID id,
        String displayName,
        String login,
        boolean active,
        boolean pendingActivation,
        boolean anonymized
) {
}
