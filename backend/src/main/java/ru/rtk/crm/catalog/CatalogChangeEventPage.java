package ru.rtk.crm.catalog;

import java.util.List;

public record CatalogChangeEventPage(List<CatalogChangeEvent> items, int page, int size, long total) {
}
