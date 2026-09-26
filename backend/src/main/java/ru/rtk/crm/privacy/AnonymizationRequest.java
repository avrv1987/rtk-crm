package ru.rtk.crm.privacy;

import java.util.List;
import java.util.UUID;

public record AnonymizationRequest(
        SubjectQuery subject,
        List<UUID> contactIds,
        List<UUID> profileIds,
        List<UUID> attachmentIds,
        List<UUID> learnerIds
) {
}
