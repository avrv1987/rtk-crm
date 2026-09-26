package ru.rtk.crm.agreement;

import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

public class AgreementException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer currentVersion;

    private AgreementException(HttpStatus status, String code, String message, Integer currentVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = currentVersion;
    }

    public static AgreementException agreementNotFound() {
        return new AgreementException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Соглашение не найдено или недоступно", null);
    }

    public static AgreementException activityNotFound() {
        return new AgreementException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Мероприятие не найдено или недоступно", null);
    }

    public static AgreementException kindNotFound() {
        return new AgreementException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Вид мероприятия не найден", null);
    }

    public static AgreementException agreementVersion(int currentVersion) {
        return new AgreementException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Соглашение уже изменили", currentVersion);
    }

    public static AgreementException activityVersion(int currentVersion) {
        return new AgreementException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Мероприятие уже изменили", currentVersion);
    }

    public static AgreementException kindVersion(int currentVersion) {
        return new AgreementException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Вид мероприятия уже изменили", currentVersion);
    }

    public static AgreementException confirmationLimit(int limit) {
        return new AgreementException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CONFIRMATION_LIMIT",
                "В выборке больше " + limit + " подтверждений; сузьте период, вуз или вид мероприятия",
                null
        );
    }

    public static AgreementException archiveSize(DataSize limit) {
        return new AgreementException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CONFIRMATION_LIMIT",
                "Файлы выборки больше " + limit.toMegabytes() + " МБ; сузьте период, вуз или вид мероприятия",
                null
        );
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer currentVersion() {
        return currentVersion;
    }
}
