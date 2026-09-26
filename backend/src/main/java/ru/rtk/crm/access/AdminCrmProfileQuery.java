package ru.rtk.crm.access;

import ru.rtk.crm.interaction.InteractionValidationException;

public record AdminCrmProfileQuery(int page, int size, AdminCrmProfileSort sort, boolean pendingOnly, String search) {
    private static final int SEARCH_LIMIT = 200;

    public static AdminCrmProfileQuery from(int page, int size, String sort, boolean pendingOnly, String search) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до 100");
        }
        String normalized = search == null || search.isBlank() ? null : search.strip();
        if (normalized != null && normalized.length() > SEARCH_LIMIT) {
            throw new InteractionValidationException("q", "Строка поиска длиннее 200 символов");
        }
        return new AdminCrmProfileQuery(page, size, AdminCrmProfileSort.parse(sort), pendingOnly, normalized);
    }

    public long offset() {
        return (long) page * size;
    }
}
