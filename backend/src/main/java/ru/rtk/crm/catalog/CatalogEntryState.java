package ru.rtk.crm.catalog;

public enum CatalogEntryState {
    ACTIVE("entry.archived = FALSE"),
    ARCHIVED("entry.archived = TRUE"),
    ALL("entry.id IS NOT NULL");

    private final String condition;

    CatalogEntryState(String condition) {
        this.condition = condition;
    }

    public String condition() {
        return condition;
    }

    public static CatalogEntryState parse(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        for (CatalogEntryState state : values()) {
            if (state.name().equals(value)) {
                return state;
            }
        }
        throw new InvalidOrganizationQueryException("state", "Такой отбор по состоянию не поддерживается");
    }
}
