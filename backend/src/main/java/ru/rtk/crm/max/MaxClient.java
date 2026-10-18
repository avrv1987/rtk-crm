package ru.rtk.crm.max;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// HTTP-клиент Bot API MAX на java.net.http без SDK. Короткие таймауты нужны потому, что отправка идёт из общего
// планировщика CRM: медленный MAX не должен задерживать хранение данных и другие задания. Токен передаётся только в
// заголовке Authorization и никогда не пишется в журнал.
@Component
@MaxEnabled
public class MaxClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxClient.class);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(5);

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;

    // Конструкторов два: без @Autowired Spring не выбирает и backend не стартует при MAX_ENABLED=true.
    @Autowired
    public MaxClient(MaxProperties properties, ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(MaxTrust.sslContext(properties.trustDir()))
                .build(), properties.apiBaseUrl(), properties.readTimeout(), objectMapper);
    }

    MaxClient(HttpClient http, URI baseUrl, Duration readTimeout, ObjectMapper objectMapper) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.readTimeout = readTimeout;
        this.objectMapper = objectMapper;
    }

    public Response get(String token, String path) {
        return send(token, path, null);
    }

    public Response post(String token, String path, Object body) {
        return send(token, path, body);
    }

    // Ответ 429 повторяется один раз после короткой паузы (Retry-After, не дольше 5 с); если лимит не прошёл,
    // вызывающий получает 429 и переносит отправку сам.
    private Response send(String token, String path, Object body) {
        Response response = once(token, path, body);
        if (response.status() == 429) {
            sleep(response.retryAfter());
            response = once(token, path, body);
        }
        return response;
    }

    private Response once(String token, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(readTimeout)
                .header("Authorization", token)
                .header("Accept", "application/json");
        try {
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), parse(response.body()),
                    response.headers().firstValue("Retry-After").map(MaxClient::seconds).orElse(Duration.ofSeconds(1)));
        } catch (IOException exception) {
            LOGGER.warn("MAX request failed path={} reason={}", pathOnly(path), exception.getClass().getSimpleName());
            throw MaxException.unavailable("MAX не ответил: " + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw MaxException.unavailable("Запрос к MAX прерван");
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            return MissingNode.getInstance();
        }
    }

    private static Duration seconds(String value) {
        try {
            Duration duration = Duration.ofSeconds(Long.parseLong(value.strip()));
            return duration.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : duration;
        } catch (NumberFormatException exception) {
            return Duration.ofSeconds(1);
        }
    }

    private static String pathOnly(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public record Response(int status, JsonNode body, Duration retryAfter) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String error() {
            String code = body.path("code").asText("");
            String message = body.path("message").asText("");
            String text = (code + " " + message).strip();
            return "HTTP " + status + (text.isEmpty() ? "" : ": " + text);
        }
    }
}
