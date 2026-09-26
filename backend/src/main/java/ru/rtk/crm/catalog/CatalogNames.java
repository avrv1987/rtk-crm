package ru.rtk.crm.catalog;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CatalogNames {
    private static final Pattern LEGAL_FORM = Pattern.compile("^(ооо|оао|пао|зао|ао|ип) ");

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

    public static String vendorKey(String value) {
        return LEGAL_FORM.matcher(normalized(value)).replaceFirst("");
    }

    static String nameKey(CatalogKind kind, String value) {
        return kind == CatalogKind.VENDORS ? vendorKey(value) : normalized(value);
    }
}
