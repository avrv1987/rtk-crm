package ru.rtk.crm.catalog;

public enum OrganizationListStatus {
    CURRENT("%s IN ('ACTIVE', 'PENDING')"),
    PENDING("%s = 'PENDING'"),
    ARCHIVED("%s = 'ARCHIVED'"),
    ALL("%s IS NOT NULL");

    private final String condition;

    OrganizationListStatus(String condition) {
        this.condition = condition;
    }

    public String condition(String statusColumn) {
        return condition.formatted(statusColumn);
    }

    public static OrganizationListStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return CURRENT;
        }
        for (OrganizationListStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new InvalidOrganizationQueryException("status", "Такой отбор по состоянию не поддерживается");
    }
}
