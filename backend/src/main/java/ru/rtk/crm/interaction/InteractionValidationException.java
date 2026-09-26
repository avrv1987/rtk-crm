package ru.rtk.crm.interaction;

public class InteractionValidationException extends RuntimeException {
    private final String field;

    public InteractionValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
