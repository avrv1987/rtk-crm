package ru.rtk.crm.catalog;

import java.util.UUID;

public record CatalogEntryRequest(String name, UUID parentId, Boolean archived, Integer version) {
}
