package ru.rtk.crm.report;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

@RestControllerAdvice
public class ReportExceptionHandler {
    private static final String RETRY_AFTER_SECONDS = "30";

    @ExceptionHandler(ReportException.class)
    public ResponseEntity<ApiError> report(ReportException exception, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.body(ApiError.of(exception.code(), exception.getMessage(), RequestId.from(request)));
    }
}
