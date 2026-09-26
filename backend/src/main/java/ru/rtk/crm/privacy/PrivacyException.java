package ru.rtk.crm.privacy;

import org.springframework.http.HttpStatus;

public class PrivacyException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer currentVersion;

    PrivacyException(HttpStatus status, String code, String message, Integer currentVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = currentVersion;
    }

    static PrivacyException contactNotFound() {
        return new PrivacyException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Контакт не найден", null);
    }

    static PrivacyException profileNotFound() {
        return new PrivacyException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Профиль CRM не найден", null);
    }

    static PrivacyException attachmentNotFound() {
        return new PrivacyException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Файл не найден", null);
    }

    static PrivacyException contactVersion(int currentVersion) {
        return new PrivacyException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Контакт уже изменили; повторите поиск", currentVersion);
    }

    static PrivacyException contactAnonymized() {
        return new PrivacyException(
                HttpStatus.CONFLICT, "PERSONAL_DATA_ANONYMIZED", "Контакт обезличен; его данные больше не изменяются", null
        );
    }

    static PrivacyException profileActive() {
        return new PrivacyException(
                HttpStatus.CONFLICT,
                "PROFILE_ACTIVE",
                "Обезличить можно только профиль с закрытым доступом; сначала заблокируйте сотрудника",
                null
        );
    }

    static PrivacyException retentionRunning() {
        return new PrivacyException(
                HttpStatus.CONFLICT, "RETENTION_RUNNING", "Сроки хранения уже применяются; дождитесь завершения", null
        );
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    Integer currentVersion() {
        return currentVersion;
    }
}
