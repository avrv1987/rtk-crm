package ru.rtk.crm.access;

import java.util.UUID;

import ru.rtk.crm.interaction.InteractionValidationException;

public record AdminCrmProfileQuery(
        int page,
        int size,
        AdminCrmProfileSort sort,
        Status status,
        String search,
        UserRole role,
        UUID teamId
) {
    private static final int SEARCH_LIMIT = 200;

    public enum Status {
        ACTIVE,
        PENDING,
        BLOCKED,
        SYNC_REQUIRED
    }

    public static AdminCrmProfileQuery from(int page, int size, String sort, boolean pendingOnly, String search) {
        return from(page, size, sort, pendingOnly ? Status.PENDING : null, search, null, null);
    }

    public static AdminCrmProfileQuery from(
            int page, int size, String sort, Status status, String search, UserRole role, UUID teamId
    ) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до 100");
        }
        return new AdminCrmProfileQuery(page, size, AdminCrmProfileSort.parse(sort), status, normalizedSearch(search), role, teamId);
    }

    static String normalizedSearch(String search) {
        String normalized = search == null || search.isBlank() ? null : search.strip();
        if (normalized != null && normalized.length() > SEARCH_LIMIT) {
            throw new InteractionValidationException("q", "Строка поиска длиннее 200 символов");
        }
        return normalized;
    }

    public long offset() {
        return (long) page * size;
    }
}
