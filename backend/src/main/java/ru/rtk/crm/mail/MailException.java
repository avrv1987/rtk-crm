package ru.rtk.crm.mail;

import org.springframework.http.HttpStatus;

public class MailException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MailException(HttpStatus status, String code, String message) {
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

    static MailException invitationInvalid() {
        return new MailException(HttpStatus.NOT_FOUND, "MAIL_INVITATION_INVALID",
                "Ссылка недействительна. Попросите администратора или менеджера выдать новую");
    }

    static MailException invitationUsed() {
        return new MailException(HttpStatus.GONE, "MAIL_INVITATION_USED",
                "Ссылка уже использована или истекла. Если пароль не сохранили, попросите выдать новый");
    }

    static MailException letterNotFound() {
        return new MailException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Письмо не найдено: возможно, оно удалено по сроку хранения");
    }
}
