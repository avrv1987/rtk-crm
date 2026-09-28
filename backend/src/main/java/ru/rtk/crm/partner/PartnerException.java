package ru.rtk.crm.partner;

import org.springframework.http.HttpStatus;

public class PartnerException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private PartnerException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static PartnerException accessClosed() {
        return new PartnerException(
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Доступ в кабинет вуза закрыт; обратитесь к вашему КАМ ИТ Школы"
        );
    }

    public static PartnerException managementForbidden() {
        return new PartnerException(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Открыть или закрыть доступ в кабинет может ответственный КАМ, руководитель команды вуза или администратор"
        );
    }

    public static PartnerException accessExists() {
        return new PartnerException(
                HttpStatus.CONFLICT, "PARTNER_ACCESS_EXISTS", "У этого контакта уже есть доступ в кабинет вуза"
        );
    }

    public static PartnerException accessMissing() {
        return new PartnerException(HttpStatus.NOT_FOUND, "NOT_FOUND", "У контакта нет доступа в кабинет вуза");
    }

    public static PartnerException documentNotFound() {
        return new PartnerException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Документ не найден или не открыт вузу");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
