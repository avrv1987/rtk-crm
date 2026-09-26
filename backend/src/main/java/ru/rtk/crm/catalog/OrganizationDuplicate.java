package ru.rtk.crm.catalog;

import java.util.UUID;

public record OrganizationDuplicate(
        UUID id,
        String name,
        OrganizationType type,
        OrganizationStatus status,
        String teamName,
        boolean exact
) {
}
