package ru.rtk.crm.max;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

// Проверка initData мини-приложения по dev.max.ru/docs/webapps/validation. Подписи доверяет только сервер:
//  - каждый параметр встречается ровно один раз (повтор ключа — отказ, иначе разбор неоднозначен);
//  - значения раскодируются из URL, пары без hash сортируются по ключу и соединяются через «\n»;
//  - секрет = HMAC-SHA256(ключ "WebAppData", сообщение — токен бота), подпись = hex(HMAC-SHA256(секрет, строка));
//  - сравнение в постоянное время. Возраст auth_date MAX не ограничивает: предел задаём сами (1 час).
// Повтор той же initData в пределах срока разрешён: клиент берёт новый сеанс по ней после 401; защищают короткий
// срок, привязка к профилю и ревизия доступа.
final class MaxInitData {
    static final int LIMIT = 4096;
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

    private MaxInitData() {
    }

    static Verified verify(String initData, String botToken, Duration maxAge, Instant now, ObjectMapper objectMapper) {
        if (initData == null || initData.isBlank() || initData.length() > LIMIT) {
            throw invalid();
        }
        Map<String, String> pairs = new TreeMap<>();
        for (String pair : initData.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw invalid();
            }
            String key = decode(pair.substring(0, separator));
            if (pairs.put(key, decode(pair.substring(separator + 1))) != null) {
                throw invalid();
            }
        }
        String hash = pairs.remove("hash");
        if (hash == null || !MaxCodes.constantTimeEquals(sign(pairs, botToken), hash.toLowerCase(Locale.ROOT))) {
            throw invalid();
        }
        Instant authDate;
        try {
            authDate = Instant.ofEpochSecond(Long.parseLong(pairs.getOrDefault("auth_date", "")));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        if (authDate.isAfter(now.plus(FUTURE_SKEW))) {
            throw invalid();
        }
        if (authDate.plus(maxAge).isBefore(now)) {
            throw new MaxException(HttpStatus.UNAUTHORIZED, "MAX_INIT_DATA_EXPIRED",
                    "Мини-приложение открыто слишком давно: закройте его и откройте снова");
        }
        try {
            JsonNode user = objectMapper.readTree(pairs.getOrDefault("user", ""));
            long userId = user == null ? 0 : user.path("id").asLong(0);
            if (userId <= 0) {
                throw invalid();
            }
            return new Verified(userId, user.path("first_name").asText(""), pairs.get("start_param"), authDate);
        } catch (IOException exception) {
            throw invalid();
        }
    }

    static String sign(Map<String, String> pairs, String botToken) {
        StringBuilder data = new StringBuilder();
        pairs.forEach((key, value) -> data.append(data.isEmpty() ? "" : "\n").append(key).append('=').append(value));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken);
        return HexFormat.of().formatHex(hmac(secret, data.toString()));
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Раскодирование как decodeURIComponent в эталоне dev.max.ru: «+» остаётся плюсом. URLDecoder превращает его в
    // пробел, и подпись значения с плюсом не сошлась бы.
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static MaxException invalid() {
        return new MaxException(HttpStatus.UNAUTHORIZED, "MAX_INIT_DATA_INVALID",
                "Не удалось проверить запуск мини-приложения: откройте его из бота CRM в MAX");
    }

    record Verified(long userId, String firstName, String startParam, Instant authDate) {
    }
}
