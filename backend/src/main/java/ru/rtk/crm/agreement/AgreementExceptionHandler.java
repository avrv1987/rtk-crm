package ru.rtk.crm.agreement;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

@RestControllerAdvice
public class AgreementExceptionHandler {
    @ExceptionHandler(AgreementException.class)
    public ResponseEntity<ApiError> agreement(AgreementException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiError(
                exception.code(),
                exception.getMessage(),
                RequestId.from(request),
                null,
                exception.currentVersion()
        ));
    }
}
