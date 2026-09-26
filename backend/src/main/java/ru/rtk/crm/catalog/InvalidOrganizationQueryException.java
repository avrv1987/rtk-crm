package ru.rtk.crm.catalog;

public class InvalidOrganizationQueryException extends RuntimeException {
    private final String field;

    public InvalidOrganizationQueryException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
