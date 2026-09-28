package ru.rtk.crm.teacherroster;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

@RestControllerAdvice
public class TeacherRosterExceptionHandler {
    @ExceptionHandler(TeacherRosterNotFoundException.class)
    public ResponseEntity<ApiError> rosterNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Список преподавателей не найден или недоступен", RequestId.from(request)));
    }
}
