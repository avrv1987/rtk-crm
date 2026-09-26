package ru.rtk.crm.catalog;

import java.util.UUID;

public record OrganizationAssignmentTarget(UUID organizationId, UUID ownerManagerId, int version) {
}
