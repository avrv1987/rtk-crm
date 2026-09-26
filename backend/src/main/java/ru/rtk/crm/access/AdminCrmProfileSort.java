package ru.rtk.crm.access;

import ru.rtk.crm.interaction.InteractionValidationException;

public enum AdminCrmProfileSort {
    DISPLAY_NAME_ASC("displayName,asc", "display_name ASC, id ASC"),
    DISPLAY_NAME_DESC("displayName,desc", "display_name DESC, id ASC"),
    ROLE_ASC("role,asc", "role ASC, id ASC"),
    ROLE_DESC("role,desc", "role DESC, id ASC"),
    ACCESS_REVISION_ASC("accessRevision,asc", "access_revision ASC, id ASC"),
    ACCESS_REVISION_DESC("accessRevision,desc", "access_revision DESC, id ASC");

    private final String value;
    private final String orderBy;

    AdminCrmProfileSort(String value, String orderBy) {
        this.value = value;
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }

    public static AdminCrmProfileSort parse(String value) {
        for (AdminCrmProfileSort sort : values()) {
            if (sort.value.equals(value)) {
                return sort;
            }
        }
        throw new InteractionValidationException("sort", "Такая сортировка не поддерживается");
    }
}
