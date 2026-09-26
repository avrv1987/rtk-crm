package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ContactEvent(
        UUID id,
        UUID contactId,
        UUID actorProfileId,
        String actorDisplayName,
        List<Change> changes,
        int version,
        OffsetDateTime occurredAt
) {
    public record Change(String field, String previousValue, String value) {
    }
}
