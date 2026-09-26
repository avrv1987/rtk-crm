package ru.rtk.crm.catalog;

import java.util.UUID;

public record CatalogReference(UUID id, String name, boolean archived, int version) {
}
