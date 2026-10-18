package ru.rtk.crm.max;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Разбор входящих событий отдельно от вебхука. Ошибка разбора не теряет событие: строка остаётся в очереди и
// повторяется, после пяти неудач получает статус ERROR и видна администратору в счётчиках.
@Service
@MaxEnabled
public class MaxInboxProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxInboxProcessor.class);
    static final int BATCH = 50;

    private final MaxQueueRepository queueRepository;
    private final MaxLinkService linkService;
    private final ObjectMapper objectMapper;

    public MaxInboxProcessor(MaxQueueRepository queueRepository, MaxLinkService linkService, ObjectMapper objectMapper) {
        this.queueRepository = queueRepository;
        this.linkService = linkService;
        this.objectMapper = objectMapper;
    }

    public int processBatch() {
        int processed = 0;
        for (MaxQueueRepository.InboxRow row : queueRepository.findReceived(BATCH)) {
            try {
                handle(row);
                queueRepository.markInboxDone(row.id(), OffsetDateTime.now());
                processed++;
            } catch (RuntimeException | IOException exception) {
                LOGGER.warn("MAX event was not processed type={} attempt={}", row.updateType(), row.attempts() + 1, exception);
                queueRepository.markInboxFailed(row.id(), exception.getClass().getSimpleName(), OffsetDateTime.now());
            }
        }
        return processed;
    }

    private void handle(MaxQueueRepository.InboxRow row) throws IOException {
        JsonNode update = objectMapper.readTree(row.payload());
        OffsetDateTime eventAt = eventTime(update);
        switch (row.updateType()) {
            case "bot_started" -> {
                JsonNode user = update.path("user");
                linkService.onStarted(
                        user.path("user_id").asLong(),
                        MaxLinkService.name(text(user, "first_name"), text(user, "last_name"), text(user, "username")),
                        text(update, "payload"),
                        eventAt,
                        row.eventKey()
                );
            }
            case "bot_stopped", "dialog_removed" -> linkService.onStopped(update.path("user").path("user_id").asLong(), eventAt);
            case "message_created" -> {
                JsonNode message = update.path("message");
                JsonNode sender = message.path("sender");
                // Отвечаем только людям и только в личном диалоге: ответ боту или в групповой чат мог бы зациклиться.
                if (!sender.path("is_bot").asBoolean(false) && sender.has("user_id")
                        && "dialog".equalsIgnoreCase(message.path("recipient").path("chat_type").asText("dialog"))) {
                    linkService.onMessage(sender.path("user_id").asLong(), row.eventKey());
                }
            }
            default -> {
                // Остальные типы событий канал не использует.
            }
        }
    }

    // timestamp в событиях MAX — миллисекунды Unix.
    static OffsetDateTime eventTime(JsonNode update) {
        long timestamp = update.path("timestamp").asLong(0);
        return timestamp <= 0 ? OffsetDateTime.now() : Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
