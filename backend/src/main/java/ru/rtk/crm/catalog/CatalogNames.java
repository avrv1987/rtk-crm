package ru.rtk.crm.catalog;

import java.util.Locale;

public final class CatalogNames {
    private CatalogNames() {
    }

    public static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    public static String normalized(String value) {
        return clean(value == null ? null : value.replaceAll("[«»\"'`„“”]", " "))
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
    }
}
