package ru.rtk.crm.catalog;

import java.util.List;

public record CatalogPage(List<CatalogLookup> items, int page, int size, long total) {
}
