package ru.rtk.crm.work;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

@RestControllerAdvice
public class WorkExceptionHandler {
    @ExceptionHandler(WorkAccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request)));
    }

    @ExceptionHandler(OrganizationDeputyNotFoundException.class)
    public ResponseEntity<ApiError> deputyNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Замещение не найдено", RequestId.from(request)));
    }
}
