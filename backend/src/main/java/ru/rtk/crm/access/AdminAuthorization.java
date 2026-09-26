package ru.rtk.crm.access;

import ru.rtk.crm.interaction.InteractionValidationException;

public final class AdminAuthorization {
    private AdminAuthorization() {
    }

    public static void requireAdmin(CrmProfile actor) {
        if (actor.role() != UserRole.ADMIN) {
            throw new AdminCrmProfileAccessDeniedException();
        }
    }

    public static String requiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("Idempotency-Key", "Не передан ключ повтора запроса Idempotency-Key");
        }
        if (value.length() > 255) {
            throw new InteractionValidationException("Idempotency-Key", "Ключ повтора запроса Idempotency-Key длиннее 255 символов");
        }
        return value;
    }

    public static String requiredRequestId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Request id is unavailable for administration audit");
        }
        if (value.length() > 64) {
            throw new IllegalStateException("Request id exceeds the administration audit limit");
        }
        return value;
    }
}
