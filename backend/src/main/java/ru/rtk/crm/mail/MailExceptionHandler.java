package ru.rtk.crm.mail;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

@RestControllerAdvice(basePackageClasses = MailExceptionHandler.class)
@MailEnabled
class MailExceptionHandler {
    @ExceptionHandler(MailException.class)
    ResponseEntity<ApiError> handle(MailException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(ApiError.of(exception.code(), exception.getMessage(), RequestId.from(request)));
    }
}
