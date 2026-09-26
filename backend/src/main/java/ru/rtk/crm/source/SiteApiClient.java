package ru.rtk.crm.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SiteApiClient {
    static final String RECORDS_PATH = "/api/crm/records";

    private final SourceProperties.Website properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SiteApiClient(SourceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.website();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    boolean configured() {
        return properties.configured();
    }

    List<SiteRecord> fetch(OffsetDateTime updatedSince) {
        List<SiteRecord> records = new ArrayList<>();
        int page = 1;
        for (int loaded = 0; ; loaded++) {
            if (loaded == properties.maxPages()) {
                throw SourceFetchException.invalidResponse(
                        "Сайт вернул больше " + properties.maxPages() + " страниц; синхронизация остановлена без изменений"
                );
            }
            JsonNode body = get(recordsUri(updatedSince, page));
            JsonNode items = body.path("items");
            if (!items.isArray()) {
                throw SourceFetchException.invalidResponse("Ответ сайта не содержит массив items (страница " + page + ")");
            }
            items.forEach(item -> records.add(SiteRecord.parse(item)));
            JsonNode nextPage = body.path("nextPage");
            if (nextPage.isMissingNode() || nextPage.isNull()) {
                return records;
            }
            if (!nextPage.isIntegralNumber() || nextPage.intValue() <= page) {
                throw SourceFetchException.invalidResponse("Поле nextPage должно быть номером следующей страницы или null");
            }
            page = nextPage.intValue();
        }
    }

    private JsonNode get(URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .GET();
        if (properties.token() != null && !properties.token().isBlank()) {
            request.header("Authorization", "Bearer " + properties.token());
        }
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw SourceFetchException.unavailable("Сайт недоступен: " + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.unavailable("Синхронизация прервана");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw SourceFetchException.unauthorized("Сайт отклонил токен доступа; проверьте SITE_TOKEN в конфигурации развёртывания");
            }
            if (response.statusCode() != 200) {
                throw SourceFetchException.unavailable("Сайт ответил HTTP " + response.statusCode());
            }
            long limit = properties.maxPageSize().toBytes();
            byte[] bytes = body.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) {
                throw SourceFetchException.invalidResponse("Страница ответа сайта больше " + properties.maxPageSize());
            }
            JsonNode json = objectMapper.readTree(bytes);
            if (json == null || !json.isObject()) {
                throw SourceFetchException.invalidResponse("Ответ сайта не является JSON-объектом");
            }
            return json;
        } catch (IOException exception) {
            throw SourceFetchException.invalidResponse("Ответ сайта не является корректным JSON");
        }
    }

    private URI recordsUri(OffsetDateTime updatedSince, int page) {
        String base = properties.baseUrl().strip();
        StringBuilder uri = new StringBuilder(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .append(RECORDS_PATH)
                .append("?page=").append(page);
        if (updatedSince != null) {
            uri.append("&updated_since=").append(URLEncoder.encode(updatedSince.toString(), StandardCharsets.UTF_8));
        }
        try {
            URI result = URI.create(uri.toString());
            if ("http".equals(result.getScheme()) || "https".equals(result.getScheme())) {
                return result;
            }
        } catch (IllegalArgumentException exception) {
            throw SourceFetchException.unavailable("Адрес сайта в конфигурации развёртывания некорректен");
        }
        throw SourceFetchException.unavailable("Адрес сайта в конфигурации развёртывания должен начинаться с http:// или https://");
    }
}
