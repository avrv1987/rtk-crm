package ru.rtk.crm.access;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CrmProfileEvent(
        UUID id,
        UUID profileId,
        UUID commandId,
        UUID actorProfileId,
        String actorDisplayName,
        String previousDisplayName,
        String displayName,
        UserRole previousRole,
        UserRole role,
        UUID previousTeamId,
        String previousTeamName,
        UUID teamId,
        String teamName,
        boolean previousActive,
        boolean active,
        boolean previousEnrolmentOperator,
        boolean enrolmentOperator,
        String requestId,
        int version,
        OffsetDateTime occurredAt
) {
}
