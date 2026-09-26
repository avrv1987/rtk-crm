package ru.rtk.crm.interaction;

import java.util.Arrays;

enum InteractionSort {
    CREATED_ASC("createdAt,asc", "i.created_at ASC, i.id ASC"),
    CREATED_DESC("createdAt,desc", "i.created_at DESC, i.id ASC"),
    UPDATED_ASC("updatedAt,asc", "i.updated_at ASC, i.id ASC"),
    UPDATED_DESC("updatedAt,desc", "i.updated_at DESC, i.id ASC"),
    NEXT_ACTION_AT_ASC("nextActionAt,asc", "i.next_action_at ASC NULLS LAST, i.updated_at DESC, i.id ASC");

    private final String value;
    private final String orderBy;

    InteractionSort(String value, String orderBy) {
        this.value = value;
        this.orderBy = orderBy;
    }

    static InteractionSort from(String value) {
        return Arrays.stream(values())
                .filter(sort -> sort.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("sort", "Такая сортировка не поддерживается"));
    }

    String orderBy() {
        return orderBy;
    }
}
