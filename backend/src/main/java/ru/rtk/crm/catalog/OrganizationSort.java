package ru.rtk.crm.catalog;

public enum OrganizationSort {
    NAME_ASC("name,asc", "name ASC, id ASC"),
    NAME_DESC("name,desc", "name DESC, id ASC"),
    UPDATED_AT_ASC("updatedAt,asc", "updated_at ASC, id ASC"),
    UPDATED_AT_DESC("updatedAt,desc", "updated_at DESC, id ASC");

    private final String value;
    private final String orderBy;

    OrganizationSort(String value, String orderBy) {
        this.value = value;
        this.orderBy = orderBy;
    }

    public String orderBy() {
        return orderBy;
    }

    public static OrganizationSort parse(String value) {
        for (OrganizationSort sort : values()) {
            if (sort.value.equals(value)) {
                return sort;
            }
        }
        throw new InvalidOrganizationQueryException("sort", "Такая сортировка не поддерживается");
    }
}
