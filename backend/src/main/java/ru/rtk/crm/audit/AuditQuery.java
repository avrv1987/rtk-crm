package ru.rtk.crm.audit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import ru.rtk.crm.interaction.InteractionValidationException;

public record AuditQuery(
        LocalDate from,
        LocalDate to,
        String actor,
        String object,
        AuditCategory category,
        int page,
        int size
) {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final int TEXT_LIMIT = 200;

    public static AuditQuery of(
            LocalDate from,
            LocalDate to,
            String actor,
            String object,
            AuditCategory category,
            int page,
            int size
    ) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до 100");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания раньше даты начала");
        }
        return new AuditQuery(from, to, text(actor, "actor"), text(object, "object"), category, page, size);
    }

    public AuditQuery firstRows(int limit) {
        return new AuditQuery(from, to, actor, object, category, 0, limit);
    }

    OffsetDateTime fromInstant() {
        return from == null ? null : from.atStartOfDay(ZONE).toOffsetDateTime();
    }

    OffsetDateTime toInstant() {
        return to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    long offset() {
        return (long) page * size;
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > TEXT_LIMIT) {
            throw new InteractionValidationException(field, "Строка поиска длиннее 200 символов");
        }
        return normalized;
    }
}
