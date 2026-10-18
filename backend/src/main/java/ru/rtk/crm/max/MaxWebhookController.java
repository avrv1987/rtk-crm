package ru.rtk.crm.max;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

// Вебхук MAX: проверить секрет, записать событие во входящую очередь и только после записи ответить 200. Разбор
// событий идёт отдельно (MaxInboxProcessor), чтобы ответ уложился в 30 секунд MAX и сбой разбора не терял событие.
// Если база недоступна, ответ не 200: MAX повторит доставку, а не посчитает событие принятым.
@RestController
@MaxEnabled
class MaxWebhookController {
    static final String SECRET_HEADER = "X-Max-Bot-Api-Secret";
    static final int BODY_LIMIT = 64 * 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxWebhookController.class);

    private final MaxSettingsService settings;
    private final MaxSettingsRepository settingsRepository;
    private final MaxQueueRepository queueRepository;
    private final ObjectMapper objectMapper;

    MaxWebhookController(
            MaxSettingsService settings,
            MaxSettingsRepository settingsRepository,
            MaxQueueRepository queueRepository,
            ObjectMapper objectMapper
    ) {
        this.settings = settings;
        this.settingsRepository = settingsRepository;
        this.queueRepository = queueRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/max/webhook")
    ResponseEntity<?> receive(
            @RequestHeader(name = SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request
    ) {
        Optional<String> expected;
        try {
            expected = settings.webhookSecret();
        } catch (DataAccessException exception) {
            return unavailable(request, exception);
        }
        if (expected.isEmpty() || !MaxCodes.constantTimeEquals(expected.get(), secret)) {
            return error(request, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Неверный секрет вебхука MAX");
        }
        if (body == null || body.length == 0) {
            return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Пустое событие MAX");
        }
        if (body.length > BODY_LIMIT) {
            return error(request, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Событие MAX больше 64 КБ");
        }
        JsonNode update;
        String key;
        try {
            update = objectMapper.readTree(body);
            key = MaxUpdateKeys.key(update);
        } catch (IOException | IllegalArgumentException exception) {
            return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Событие MAX не разобрано");
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            queueRepository.insertInbox(key, update.path("update_type").asText(), new String(body, StandardCharsets.UTF_8), now);
            settingsRepository.touchLastEvent(now);
        } catch (DataAccessException exception) {
            return unavailable(request, exception);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private ResponseEntity<ApiError> unavailable(HttpServletRequest request, DataAccessException exception) {
        LOGGER.error("MAX webhook event was not stored", exception);
        return error(request, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Событие не сохранено; MAX повторит доставку");
    }

    private static ResponseEntity<ApiError> error(HttpServletRequest request, HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message, RequestId.from(request)));
    }
}
