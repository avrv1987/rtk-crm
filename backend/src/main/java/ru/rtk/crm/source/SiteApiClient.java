package ru.rtk.crm.source;

import java.io.ByteArrayInputStream;
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
import ru.rtk.crm.enrolment.PaidOrderBatch;
import ru.rtk.crm.enrolment.PaidOrderParser;
import ru.rtk.crm.interaction.InteractionValidationException;

@Component
public class SiteApiClient {
    static final String RECORDS_PATH = "/api/crm/records";

    private final SourceProperties.Website properties;
    private final ObjectMapper objectMapper;
    private final PaidOrderParser paidOrderParser;
    private final HttpClient httpClient;

    public SiteApiClient(SourceProperties properties, ObjectMapper objectMapper, PaidOrderParser paidOrderParser) {
        this.properties = properties.website();
        this.objectMapper = objectMapper;
        this.paidOrderParser = paidOrderParser;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    boolean configured() {
        return properties.configured();
    }

    SiteResponse fetch(OffsetDateTime updatedSince) {
        List<SiteRecord> records = new ArrayList<>();
        int page = 1;
        for (int loaded = 0; ; loaded++) {
            if (loaded == properties.maxPages()) {
                throw SourceFetchException.invalidResponse(
                        "Сайт вернул больше " + properties.maxPages() + " страниц; синхронизация остановлена без изменений"
                );
            }
            byte[] bytes = get(recordsUri(updatedSince, page));
            JsonNode body = json(bytes);
            if (body.isArray() && page == 1) {
                return new SiteResponse(List.of(), paidOrders(bytes));
            }
            if (!body.isObject()) {
                throw SourceFetchException.invalidResponse("Ответ сайта не является JSON-объектом или массивом оплат");
            }
            JsonNode items = body.path("items");
            if (!items.isArray()) {
                throw SourceFetchException.invalidResponse("Ответ сайта не содержит массив items (страница " + page + ")");
            }
            items.forEach(item -> records.add(SiteRecord.parse(item)));
            JsonNode nextPage = body.path("nextPage");
            if (nextPage.isMissingNode() || nextPage.isNull()) {
                return new SiteResponse(List.copyOf(records), null);
            }
            if (!nextPage.isIntegralNumber() || nextPage.intValue() <= page) {
                throw SourceFetchException.invalidResponse("Поле nextPage должно быть номером следующей страницы или null");
            }
            page = nextPage.intValue();
        }
    }

    private PaidOrderBatch paidOrders(byte[] bytes) {
        try {
            return paidOrderParser.parse(new ByteArrayInputStream(bytes));
        } catch (InteractionValidationException exception) {
            throw SourceFetchException.invalidResponse("Ответ сайта с оплатами отклонён: " + exception.getMessage());
        }
    }

    private JsonNode json(byte[] bytes) {
        JsonNode json;
        try {
            json = objectMapper.readTree(bytes);
        } catch (IOException exception) {
            throw SourceFetchException.invalidResponse("Ответ сайта не является корректным JSON");
        }
        if (json == null || json.isMissingNode()) {
            throw SourceFetchException.invalidResponse("Ответ сайта не является JSON-объектом или массивом оплат");
        }
        return json;
    }

    private byte[] get(URI uri) {
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
            throw SourceFetchException.unreachable("Сайт", exception);
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
            return bytes;
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

    record SiteResponse(List<SiteRecord> records, PaidOrderBatch paidOrders) {
    }
}
