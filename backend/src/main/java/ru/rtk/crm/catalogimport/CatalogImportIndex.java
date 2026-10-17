package ru.rtk.crm.catalogimport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import ru.rtk.crm.catalog.CatalogNames;

final class CatalogImportIndex<T extends ImportKeyed> {
    private final Map<UUID, T> byId = new HashMap<>();
    private final Map<String, T> byKey = new HashMap<>();
    private final Map<String, List<T>> byNaturalKey = new HashMap<>();

    CatalogImportIndex(List<T> items, Function<T, String> naturalKey) {
        for (T item : items) {
            byId.put(item.id(), item);
            if (item.externalKey() != null) {
                byKey.put(item.externalKey(), item);
            }
            byNaturalKey.computeIfAbsent(naturalKey.apply(item), ignored -> new ArrayList<>()).add(item);
        }
    }

    Optional<T> byId(UUID id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    Optional<T> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    List<T> byNaturalKey(String naturalKey) {
        return byNaturalKey.getOrDefault(naturalKey, List.of());
    }

    // Названия сравниваются без учёта регистра, лишних пробелов (включая неразрывные) и различия «е/ё»:
    // так «Ёлка» из файла находит «елка» в каталоге.
    static String normalized(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    static String naturalKey(Object parent, String name) {
        return (parent == null ? "" : parent.toString()) + "|" + normalized(name);
    }

    // Название ПО сравнивается по правилам каталога (CatalogNames.normalized): ещё и без кавычек, поэтому
    // «Касперский» и Касперский — одно ПО. Ключ включает вендора: одинаковые названия у разных вендоров не смешиваются.
    static String productKey(Object parent, String name) {
        return (parent == null ? "" : parent.toString()) + "|" + CatalogNames.normalized(name);
    }
}
