package ru.rtk.crm.interaction;

public class InteractionConflictException extends RuntimeException {
    private final String code;
    private final Integer currentVersion;

    private InteractionConflictException(String code, String message, Integer currentVersion) {
        super(message);
        this.code = code;
        this.currentVersion = currentVersion;
    }

    public static InteractionConflictException version(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Карточку взаимодействия уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException organizationVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Карточку вуза уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException crmProfileVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Профиль уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException workflowTemplateVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Шаблон процесса уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException catalogImportVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Импорт уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException teamVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Команду уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException lastActiveAdministrator() {
        return new InteractionConflictException(
                "LAST_ACTIVE_ADMIN",
                "Нельзя оставить систему без активного администратора",
                null
        );
    }

    public static InteractionConflictException idempotency() {
        return new InteractionConflictException(
                "IDEMPOTENCY_CONFLICT",
                "Этот ключ повтора уже использован для другого запроса; обновите страницу и повторите действие",
                null
        );
    }

    public String code() {
        return code;
    }

    public Integer currentVersion() {
        return currentVersion;
    }
}
