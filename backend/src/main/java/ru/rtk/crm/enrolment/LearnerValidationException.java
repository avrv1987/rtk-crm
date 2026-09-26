package ru.rtk.crm.enrolment;

import java.util.Map;

public class LearnerValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public LearnerValidationException(Map<String, String> fieldErrors) {
        super("Проверьте поля анкеты");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
