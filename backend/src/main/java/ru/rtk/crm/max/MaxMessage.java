package ru.rtk.crm.max;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Тело NewMessageBody из схемы Bot API MAX: текст без разметки (в названиях вузов бывают символы markdown) и
// inline-клавиатура по кнопке в ряду. Поле notify не хранится: оно выбирается в момент отправки по времени суток.
public record MaxMessage(String text, List<Button> buttons) {
    static final int TEXT_LIMIT = 4000;

    public MaxMessage {
        text = text.length() > TEXT_LIMIT ? text.substring(0, TEXT_LIMIT - 1) + "…" : text;
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    public static MaxMessage text(String text) {
        return new MaxMessage(text, List.of());
    }

    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        if (!buttons.isEmpty()) {
            List<List<Map<String, Object>>> rows = new ArrayList<>();
            for (Button button : buttons) {
                rows.add(List.of(button.json()));
            }
            body.put("attachments", List.of(Map.of("type", "inline_keyboard", "payload", Map.of("buttons", rows))));
        }
        return body;
    }

    public record Button(String type, String text, String url, String webApp, String payload) {
        public static Button link(String text, String url) {
            return new Button("link", text, url, null, null);
        }

        // Кнопка open_app открывает мини-приложение бота; payload по схеме — до 512 символов [A-Za-z0-9_-].
        public static Button openApp(String text, String botName, String payload) {
            return new Button("open_app", text, null, botName, payload);
        }

        Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", type);
            json.put("text", text);
            if (url != null) {
                json.put("url", url);
            }
            if (webApp != null) {
                json.put("web_app", webApp);
            }
            if (payload != null) {
                json.put("payload", payload);
            }
            return json;
        }
    }
}
