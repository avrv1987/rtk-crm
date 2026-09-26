package ru.rtk.crm.catalog;

import java.util.UUID;

public record AdminOrganization(
        UUID id,
        String name,
        OrganizationType type,
        UUID teamId,
        String teamName,
        UUID ownerManagerId,
        String ownerManagerName,
        int version,
        OrganizationStatus status,
        String city,
        String website,
        String inn
) {
}
