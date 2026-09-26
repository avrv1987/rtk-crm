package ru.rtk.crm.privacy;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubjectMention(
        UUID interactionId,
        String interactionTitle,
        String organizationName,
        MentionPlace place,
        String text,
        OffsetDateTime occurredAt
) {
}
