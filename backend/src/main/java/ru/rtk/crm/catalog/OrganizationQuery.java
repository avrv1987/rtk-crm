package ru.rtk.crm.catalog;

public record OrganizationQuery(int page, int size, OrganizationSort sort, String search, boolean requiresAssignment) {
    private static final int MAX_SEARCH_LENGTH = 200;

    public static OrganizationQuery from(int page, int size, String sort, String search, boolean requiresAssignment) {
        if (page < 0) {
            throw new InvalidOrganizationQueryException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InvalidOrganizationQueryException("size", "Размер страницы должен быть от 1 до 100");
        }
        String normalizedSearch = search == null || search.isBlank() ? null : search.strip();
        if (normalizedSearch != null && normalizedSearch.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidOrganizationQueryException("q", "Строка поиска длиннее " + MAX_SEARCH_LENGTH + " символов");
        }
        return new OrganizationQuery(page, size, OrganizationSort.parse(sort), normalizedSearch, requiresAssignment);
    }

    public long offset() {
        return (long) page * size;
    }
}
