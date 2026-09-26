package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Contact(
        UUID id,
        UUID organizationId,
        String name,
        String position,
        String email,
        String phone,
        int version,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        ContactRole role,
        boolean primary,
        boolean inactive,
        OffsetDateTime confirmedAt,
        UUID confirmedBy,
        String confirmedByName,
        PersonalDataStatus personalDataStatus
) {
}
