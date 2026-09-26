package ru.rtk.crm.access;

import ru.rtk.crm.interaction.InteractionValidationException;

public record AdminCrmProfileQuery(int page, int size, AdminCrmProfileSort sort, boolean pendingOnly) {
    public static AdminCrmProfileQuery from(int page, int size, String sort, boolean pendingOnly) {
        if (page < 0) {
            throw new InteractionValidationException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InteractionValidationException("size", "Размер страницы должен быть от 1 до 100");
        }
        return new AdminCrmProfileQuery(page, size, AdminCrmProfileSort.parse(sort), pendingOnly);
    }

    public long offset() {
        return (long) page * size;
    }
}
