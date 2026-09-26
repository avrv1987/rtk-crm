package ru.rtk.crm.interaction;

import java.util.Arrays;

enum WorkflowTemplateSort {
    NAME_ASC("name,asc", "name ASC, id ASC"),
    NAME_DESC("name,desc", "name DESC, id ASC"),
    VERSION_ASC("version,asc", "version ASC, id ASC"),
    VERSION_DESC("version,desc", "version DESC, id ASC");

    private final String value;
    private final String orderBy;

    WorkflowTemplateSort(String value, String orderBy) {
        this.value = value;
        this.orderBy = orderBy;
    }

    static WorkflowTemplateSort from(String value) {
        return Arrays.stream(values())
                .filter(sort -> sort.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("sort", "Такая сортировка не поддерживается"));
    }

    String orderBy() {
        return orderBy;
    }
}
