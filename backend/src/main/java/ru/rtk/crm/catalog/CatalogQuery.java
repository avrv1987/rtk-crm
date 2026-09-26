package ru.rtk.crm.catalog;

public record CatalogQuery(int page, int size) {
    public static CatalogQuery from(int page, int size) {
        if (page < 0) {
            throw new InvalidOrganizationQueryException("page", "Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > 100) {
            throw new InvalidOrganizationQueryException("size", "Размер страницы должен быть от 1 до 100");
        }
        return new CatalogQuery(page, size);
    }

    public int offset() {
        try {
            return Math.multiplyExact(page, size);
        } catch (ArithmeticException exception) {
            throw new InvalidOrganizationQueryException("page", "Слишком большой номер страницы");
        }
    }
}
