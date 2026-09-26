package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

public record OrganizationBulkAssignmentRequest(List<Item> items) {
    public record Item(UUID organizationId, Integer version, UUID ownerManagerId) {
    }
}
