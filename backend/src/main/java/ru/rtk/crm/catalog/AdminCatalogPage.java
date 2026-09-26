package ru.rtk.crm.catalog;

import java.util.List;

public record AdminCatalogPage(List<AdminCatalogEntry> items, int page, int size, long total) {
}
