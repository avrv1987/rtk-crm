package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

public record OrganizationBulkAssignmentResult(
        List<OrganizationAssignmentResult> assigned,
        List<UUID> unchangedOrganizationIds
) {
}
