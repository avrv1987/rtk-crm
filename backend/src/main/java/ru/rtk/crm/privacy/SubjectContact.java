package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.UUID;

import ru.rtk.crm.catalog.PersonalDataStatus;

public record SubjectContact(
        UUID id,
        UUID organizationId,
        String organizationName,
        String name,
        String position,
        String email,
        String phone,
        PersonalDataStatus status,
        int version,
        String createdByName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int interactionsCount
) {
}
