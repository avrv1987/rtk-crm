package ru.rtk.crm.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import ru.rtk.crm.security.RequestId;

@RestController
public class ApiErrorController implements ErrorController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("/error")
    public ResponseEntity<?> error(HttpServletRequest request) {
        int statusCode = errorStatus(request);
        String originalUri = String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        if (statusCode >= 500) {
            LOGGER.error(
                    "Request failed status={} uri={}",
                    statusCode,
                    originalUri,
                    errorException(request)
            );
        }
        if (!originalUri.startsWith("/api/")) {
            return ResponseEntity.status(statusCode).build();
        }
        ApiError error = switch (statusCode) {
            case 400 -> ApiError.of("VALIDATION_ERROR", "Проверьте введённые данные", RequestId.from(request));
            case 401 -> ApiError.of("UNAUTHENTICATED", "Требуется вход в систему", RequestId.from(request));
            case 403 -> ApiError.of("FORBIDDEN", "Доступ запрещён", RequestId.from(request));
            case 404 -> ApiError.of("NOT_FOUND", "Не найдено или недоступно", RequestId.from(request));
            case 405 -> ApiError.of("METHOD_NOT_ALLOWED", "Такое действие с ресурсом не поддерживается", RequestId.from(request));
            case 409 -> ApiError.of("CONFLICT", "Данные уже изменились; обновите страницу", RequestId.from(request));
            case 413 -> ApiError.of("PAYLOAD_TOO_LARGE", "Файл больше допустимого размера", RequestId.from(request));
            case 415 -> ApiError.of("UNSUPPORTED_MEDIA_TYPE", "Формат данных не поддерживается", RequestId.from(request));
            case 429 -> ApiError.of("RATE_LIMITED", "Слишком много запросов; повторите позже", RequestId.from(request));
            case 503 -> ApiError.of("DEPENDENCY_UNAVAILABLE", "Сервис временно недоступен; повторите позже", RequestId.from(request));
            default -> statusCode < 500
                    ? ApiError.of("BAD_REQUEST", "Некорректный запрос", RequestId.from(request))
                    : ApiError.of("INTERNAL_ERROR", "Внутренняя ошибка сервера; повторите позже", RequestId.from(request));
        };
        return ResponseEntity.status(statusCode).body(error);
    }

    private Throwable errorException(HttpServletRequest request) {
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (exception == null) {
            exception = request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
        }
        return exception instanceof Throwable throwable ? throwable : null;
    }

    private int errorStatus(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return value instanceof Integer statusCode ? statusCode : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
