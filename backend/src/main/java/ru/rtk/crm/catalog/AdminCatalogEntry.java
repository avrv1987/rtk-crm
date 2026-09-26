package ru.rtk.crm.catalog;

import java.util.UUID;

public record AdminCatalogEntry(
        UUID id,
        String name,
        UUID parentId,
        String parentName,
        boolean archived,
        int version
) {
}
