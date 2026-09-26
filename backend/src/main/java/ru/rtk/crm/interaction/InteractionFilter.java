package ru.rtk.crm.interaction;

import java.util.UUID;

record InteractionFilter(UUID organizationId, String search, InteractionDue due, String stage) {
    private static final int MAX_TEXT_LENGTH = 200;

    static InteractionFilter from(UUID organizationId, String search, String due, String stage) {
        return new InteractionFilter(
                organizationId,
                optionalText(search, "q"),
                InteractionDue.from(due),
                optionalText(stage, "stage")
        );
    }

    private static String optionalText(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InteractionValidationException(field, "Значение длиннее " + MAX_TEXT_LENGTH + " символов");
        }
        return text;
    }
}
