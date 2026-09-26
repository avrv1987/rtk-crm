package ru.rtk.crm.catalog;

import java.util.Locale;

public final class SearchPattern {
    public static final String LIKE_ESCAPE = "ESCAPE '\\'";

    private SearchPattern() {
    }

    public static String contains(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
