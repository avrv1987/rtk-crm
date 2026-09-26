package ru.rtk.crm.source;

import org.springframework.http.HttpStatus;

public class SourceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer currentVersion;

    SourceException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    private SourceException(HttpStatus status, String code, String message, Integer currentVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = currentVersion;
    }

    static SourceException adminRequired() {
        return new SourceException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Источники данных доступны только администратору");
    }

    static SourceException mappingNotFound() {
        return new SourceException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Сопоставление источника не найдено");
    }

    static SourceException mappingVersion(int currentVersion) {
        return new SourceException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "Сопоставление уже изменили; обновите список и повторите", currentVersion);
    }

    static SourceException recordResolved() {
        return new SourceException(HttpStatus.CONFLICT, "CONFLICT",
                "Запись уже сопоставлена с организацией; обновите список заявок");
    }

    static SourceException reviewForbidden() {
        return new SourceException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Сопоставлять заявки и создавать организации из них может руководитель команды или администратор");
    }

    static SourceException recordNotFound() {
        return new SourceException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Запись источника не найдена");
    }

    static SourceException notConfigured(SourceCode source) {
        return new SourceException(
                HttpStatus.CONFLICT,
                "SOURCE_NOT_CONFIGURED",
                source == SourceCode.MOODLE
                        ? "Moodle не настроен: задайте MOODLE_BASE_URL, MOODLE_TOKEN и MOODLE_COURSE_IDS в конфигурации развёртывания"
                        : "Адрес сайта не задан в конфигурации развёртывания (SITE_BASE_URL)"
        );
    }

    static SourceException alreadyRunning() {
        return new SourceException(
                HttpStatus.CONFLICT,
                "SYNC_ALREADY_RUNNING",
                "Синхронизация этого источника уже выполняется; дождитесь её завершения"
        );
    }

    static SourceException capacityExceeded() {
        return new SourceException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SYNC_CAPACITY_EXCEEDED",
                "Исполнитель синхронизаций занят; повторите запуск позже"
        );
    }

    static SourceException learningNotMapped() {
        return new SourceException(
                HttpStatus.CONFLICT,
                "LMS_NOT_MAPPED",
                "Для вуза и программы этой карточки нет сопоставленных курсов Moodle; сопоставление выполняет администратор"
                        + " в разделе «Источники данных»"
        );
    }

    static SourceException learningSyncFailed(String reason) {
        return new SourceException(
                HttpStatus.BAD_GATEWAY,
                "LMS_SYNC_FAILED",
                "Данные LMS не обновлены, прежние значения сохранены: " + (reason == null ? "ошибка синхронизации" : reason)
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
