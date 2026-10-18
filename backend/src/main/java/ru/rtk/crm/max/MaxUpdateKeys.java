package ru.rtk.crm.max;

import com.fasterxml.jackson.databind.JsonNode;

// Ключ повтора события вебхука по полям схемы Bot API: универсального update_id в MAX нет. MAX повторяет то же
// событие с тем же timestamp, поэтому повтор даёт тот же ключ, а два разных события — разные:
//  - message_created — идентификатор сообщения body.mid;
//  - message_callback — callback_id вместе с временем нажатия: callback_id обозначает клавиатуру, а не нажатие,
//    и без времени два нажатия одной кнопки склеились бы;
//  - события диалога (bot_started, bot_stopped, dialog_*) — тип, пользователь и timestamp.
final class MaxUpdateKeys {
    static final int LIMIT = 200;

    private MaxUpdateKeys() {
    }

    static String key(JsonNode update) {
        String type = update.path("update_type").asText("");
        if (type.isBlank()) {
            throw new IllegalArgumentException("update_type is missing");
        }
        String key = switch (type) {
            case "message_created" -> {
                String mid = update.path("message").path("body").path("mid").asText("");
                yield mid.isBlank() ? fallback(type, update) : type + ":" + mid;
            }
            case "message_callback" -> {
                JsonNode callback = update.path("callback");
                String callbackId = callback.path("callback_id").asText("");
                yield callbackId.isBlank()
                        ? fallback(type, update)
                        : type + ":" + callbackId + ":" + callback.path("timestamp").asLong(update.path("timestamp").asLong());
            }
            default -> fallback(type, update);
        };
        return key.length() > LIMIT ? type.substring(0, Math.min(type.length(), 40)) + ":" + MaxCodes.sha256(key) : key;
    }

    static long userId(JsonNode update) {
        JsonNode user = update.path("user");
        if (user.isMissingNode()) {
            user = update.path("callback").path("user");
        }
        if (user.isMissingNode()) {
            user = update.path("message").path("sender");
        }
        return user.path("user_id").asLong(0);
    }

    private static String fallback(String type, JsonNode update) {
        long user = userId(update);
        String subject = user != 0 ? String.valueOf(user) : update.path("chat_id").asText("");
        return type + ":" + subject + ":" + update.path("timestamp").asLong();
    }
}
