package ru.rtk.crm.catalog;

import java.util.UUID;

// groupName — направление программы или вендор продукта; у направлений пусто.
public record CatalogLookup(UUID id, String name, boolean archived, int version, String groupName) {
}
