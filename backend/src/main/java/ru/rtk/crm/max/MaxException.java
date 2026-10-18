package ru.rtk.crm.max;

import org.springframework.http.HttpStatus;

public class MaxException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MaxException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    static MaxException notConfigured() {
        return new MaxException(HttpStatus.CONFLICT, "MAX_NOT_CONFIGURED",
                "Бот MAX не настроен: администратор должен ввести токен и проверить связь");
    }

    static MaxException unavailable(String message) {
        return new MaxException(HttpStatus.SERVICE_UNAVAILABLE, "MAX_UNAVAILABLE", message);
    }

    static MaxException forbidden() {
        return new MaxException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Канал MAX недоступен для этой роли");
    }
}
