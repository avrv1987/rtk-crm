package ru.rtk.crm.attachment;

public class AttachmentValidationException extends RuntimeException {
    private final String field;

    public AttachmentValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
