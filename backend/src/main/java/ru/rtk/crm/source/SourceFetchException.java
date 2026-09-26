package ru.rtk.crm.source;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

public class SourceFetchException extends RuntimeException {
    private final String code;

    SourceFetchException(String code, String message) {
        super(message);
        this.code = code;
    }

    static SourceFetchException unavailable(String message) {
        return new SourceFetchException("SOURCE_UNAVAILABLE", message);
    }

    static SourceFetchException unreachable(String subject, IOException exception) {
        String reason = exception instanceof HttpTimeoutException
                ? "истекло время ожидания ответа"
                : exception instanceof ConnectException ? "соединение не установлено" : "ошибка сети";
        return unavailable(subject + " недоступен: " + reason);
    }

    static SourceFetchException unauthorized(String message) {
        return new SourceFetchException("SOURCE_UNAUTHORIZED", message);
    }

    static SourceFetchException invalidResponse(String message) {
        return new SourceFetchException("SOURCE_INVALID_RESPONSE", message);
    }

    String code() {
        return code;
    }
}
