package ru.rtk.crm.catalog;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import ru.rtk.crm.report.ReportRequest;

public record OrganizationHistoryQuery(
        Set<OrganizationHistoryKind> kinds,
        OffsetDateTime fromAt,
        OffsetDateTime toAt,
        int page,
        int size
) {
    private static final int MAX_SIZE = 100;

    public static OrganizationHistoryQuery from(List<String> kinds, String from, String to, int page, int size) {
        if (page < 0) {
            throw new InvalidOrganizationQueryException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidOrganizationQueryException("size", "Размер страницы должен быть от 1 до " + MAX_SIZE);
        }
        LocalDate fromDate = date("from", from);
        LocalDate toDate = date("to", to);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new InvalidOrganizationQueryException("from", "Начало периода позже его конца");
        }
        return new OrganizationHistoryQuery(
                kindSet(kinds),
                fromDate == null ? null : fromDate.atStartOfDay(ReportRequest.ZONE).toOffsetDateTime(),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay(ReportRequest.ZONE).toOffsetDateTime(),
                page,
                size
        );
    }

    long offset() {
        return (long) page * size;
    }

    boolean includes(Set<OrganizationHistoryKind> group) {
        return kinds.isEmpty() || group.stream().anyMatch(kinds::contains);
    }

    private static Set<OrganizationHistoryKind> kindSet(List<String> values) {
        Set<OrganizationHistoryKind> kinds = EnumSet.noneOf(OrganizationHistoryKind.class);
        for (String value : values == null ? List.<String>of() : values) {
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    kinds.add(OrganizationHistoryKind.valueOf(part.trim()));
                } catch (IllegalArgumentException exception) {
                    throw new InvalidOrganizationQueryException("kinds", "Неизвестный вид события");
                }
            }
        }
        return kinds;
    }

    private static LocalDate date(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeException exception) {
            throw new InvalidOrganizationQueryException(field, "Укажите дату в формате ГГГГ-ММ-ДД");
        }
    }
}
