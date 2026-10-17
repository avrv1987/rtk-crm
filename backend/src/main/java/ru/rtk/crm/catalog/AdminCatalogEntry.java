package ru.rtk.crm.catalog;

import java.util.UUID;

public record AdminCatalogEntry(
        UUID id,
        String name,
        UUID parentId,
        String parentName,
        boolean archived,
        int version,
        Boolean laborMarket
) {
    public AdminCatalogEntry(UUID id, String name, UUID parentId, String parentName, boolean archived, int version) {
        this(id, name, parentId, parentName, archived, version, null);
    }
}
