package ru.rtk.crm.catalog;

import java.util.UUID;

import ru.rtk.crm.access.UserRole;

public record OrganizationAssignmentProfile(
        UUID id,
        String displayName,
        UserRole role,
        UUID teamId,
        boolean active
) {
}
