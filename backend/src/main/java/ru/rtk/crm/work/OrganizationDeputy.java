package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrganizationDeputy(
        UUID id,
        UUID organizationId,
        UUID deputyProfileId,
        String deputyDisplayName,
        LocalDate startsOn,
        LocalDate endsOn,
        OffsetDateTime endsAt,
        UUID actorProfileId,
        String actorDisplayName,
        OffsetDateTime createdAt,
        OffsetDateTime endedAt,
        UUID endedByProfileId,
        String endedByDisplayName,
        Status status
) {
    public enum Status {
        SCHEDULED,
        ACTIVE,
        ENDED
    }
}
