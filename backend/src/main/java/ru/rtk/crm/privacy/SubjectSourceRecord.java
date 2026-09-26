package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubjectSourceRecord(
        UUID id,
        String source,
        String recordType,
        String externalId,
        String status,
        String organizationName,
        OffsetDateTime submittedAt
) {
}
