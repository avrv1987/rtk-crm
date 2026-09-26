package ru.rtk.crm.catalog;

import java.util.UUID;

public record OrganizationDetailsRequest(
        String name,
        OrganizationType type,
        String city,
        String website,
        String inn,
        UUID teamId,
        Integer version
) {
}
