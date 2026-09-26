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

    public static InteractionConflictException contactVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Контакт уже изменили",
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

    public static InteractionConflictException catalogEntryVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Запись справочника уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException attachmentVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Сведения о документе уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException attachmentReplaced() {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "У документа уже есть новая версия; обновите карточку",
                null
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

    public static InteractionConflictException learnerVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Анкету слушателя уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException learnerAnonymized() {
        return new InteractionConflictException(
                "PERSONAL_DATA_ANONYMIZED",
                "Анкета слушателя обезличена; её данные больше не показываются и не изменяются",
                null
        );
    }

    public static InteractionConflictException learnerRestricted() {
        return new InteractionConflictException(
                "PERSONAL_DATA_RESTRICTED",
                "Обработка анкеты ограничена по обращению субъекта; снять ограничение может администратор",
                null
        );
    }

    public static InteractionConflictException enrolmentStreamVersion(int currentVersion) {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Поток уже изменили",
                currentVersion
        );
    }

    public static InteractionConflictException questionnaireChanged() {
        return new InteractionConflictException(
                "VERSION_CONFLICT",
                "Анкеты потока изменились после предпросмотра; постройте предпросмотр заново",
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
