package ru.rtk.crm.catalogimport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Чтение ячеек строки для профилей переноса данных: ошибки пишутся в протокол строки, а не прерывают предпросмотр.
final class ImportCells {
    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter RUSSIAN_DATE = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}\\.\\d{1,2}\\.\\d{4}");
    private static final Pattern DATE_TIME = Pattern.compile("^(\\S+?)(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");

    private ImportCells() {
    }

    static String optional(PlannedRow row, String field, int maxLength) {
        String value = CatalogImportIndex.clean(row.values.get(field));
        if (value.length() > maxLength) {
            row.error(field, "Длиннее " + maxLength + " символов");
        }
        row.values.put(field, value);
        return value;
    }

    static String required(PlannedRow row, String field, int maxLength) {
        String value = optional(row, field, maxLength);
        if (value.isEmpty()) {
            row.error(field, "Заполните значение");
        }
        return value;
    }

    // Текст комментария хранится с переводами строк: схлопываются только пробелы по краям.
    static String text(PlannedRow row, String field, int maxLength) {
        String raw = row.values.get(field);
        String value = ru.rtk.crm.web.VisibleText.blank(raw) ? "" : raw.strip();
        if (value.length() > maxLength) {
            row.error(field, "Длиннее " + maxLength + " символов");
        }
        row.values.put(field, value);
        return value;
    }

    static UUID uuid(PlannedRow row, String field) {
        String value = optional(row, field, 36);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            row.error(field, "Укажите UUID в формате 8-4-4-4-12");
            return null;
        }
    }

    static LocalDate date(PlannedRow row, String field) {
        String value = optional(row, field, 40);
        if (value.isEmpty()) {
            return null;
        }
        LocalDate date = parseDate(value);
        if (date == null) {
            row.error(field, dateError(value, "Укажите дату в формате ГГГГ-ММ-ДД или ДД.ММ.ГГГГ"));
        }
        return date;
    }

    // Дата события: «ГГГГ-ММ-ДД» или «ДД.ММ.ГГГГ», можно со временем «ЧЧ:ММ». Без времени — начало дня по Москве.
    static OffsetDateTime dateTime(PlannedRow row, String field) {
        String value = optional(row, field, 40);
        if (value.isEmpty()) {
            return null;
        }
        Matcher matcher = DATE_TIME.matcher(value);
        LocalDate date = matcher.matches() ? parseDate(matcher.group(1)) : null;
        if (date == null) {
            row.error(field, dateError(matcher.matches() ? matcher.group(1) : value,
                    "Укажите дату в формате ГГГГ-ММ-ДД или ДД.ММ.ГГГГ, время — ЧЧ:ММ"));
            return null;
        }
        LocalTime time = LocalTime.MIDNIGHT;
        if (matcher.group(2) != null) {
            int hours = Integer.parseInt(matcher.group(2));
            int minutes = Integer.parseInt(matcher.group(3));
            int seconds = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            if (hours > 23 || minutes > 59 || seconds > 59) {
                row.error(field, "Время указано неверно");
                return null;
            }
            time = LocalTime.of(hours, minutes, seconds);
        }
        return LocalDateTime.of(date, time).atZone(ZONE).toOffsetDateTime();
    }

    // Формат верный, а даты нет (29.02.2025, 31.02.2026) — говорим именно это, а не «неверный формат».
    private static String dateError(String value, String formatError) {
        return DATE_SHAPE.matcher(value).matches() ? "Такой даты нет: проверьте день и месяц (" + value + ")" : formatError;
    }

    static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, RUSSIAN_DATE);
            } catch (DateTimeParseException exception) {
                return null;
            }
        }
    }

    // Вуз ищется по ключу, затем по названию. Импорт истории и договоров вузы не создаёт: их загружает профиль «Каталог по ТЗ».
    static Optional<ImportOrganization> organization(PlannedRow row, CatalogImportIndex<ImportOrganization> organizations) {
        String name = required(row, "organizationName", 300);
        String key = optional(row, "organizationExternalKey", 200);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        if (!key.isEmpty()) {
            Optional<ImportOrganization> byKey = organizations.byKey(key);
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        List<ImportOrganization> found = organizations.byNaturalKey(CatalogImportIndex.normalized(name));
        if (found.size() > 1) {
            row.conflict("organizationName", "В CRM несколько вузов с таким названием; укажите ключ вуза");
            return Optional.empty();
        }
        if (found.isEmpty()) {
            row.error("organizationName", "Вуз «" + name + "» не найден в CRM; сначала загрузите вузы профилем «Каталог по ТЗ»");
            return Optional.empty();
        }
        return Optional.of(found.getFirst());
    }
}
