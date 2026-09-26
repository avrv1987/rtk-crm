package ru.rtk.crm.catalog;

import java.util.UUID;

public record CatalogLookup(UUID id, String name, boolean archived, int version) {
}
