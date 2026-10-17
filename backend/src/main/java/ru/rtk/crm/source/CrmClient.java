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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class CrmClient {
    static final String ORGANIZATIONS_PATH = "/api/crm-exchange/organizations";
    static final String CONTACTS_PATH = "/api/crm-exchange/contacts";
    static final String EVENTS_PATH = "/api/crm-exchange/events";
    private static final String SUBJECT = "CRM заказчика";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CrmClient(SourceProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.crm().connectTimeout())
                // Токен идёт в заголовке Authorization: переход на другой адрес отдал бы его чужому хосту.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    void check(SourceProperties.Crm settings) {
        JsonNode body = json(get(settings, uri(settings, ORGANIZATIONS_PATH, OffsetDateTime.now(), 1)));
        if (!body.path("items").isArray()) {
            throw SourceFetchException.invalidResponse("Ответ CRM заказчика не содержит массив items");
        }
    }

    CrmFetch fetch(SourceProperties.Crm settings, OffsetDateTime updatedSince) {
        return new CrmFetch(
                pages(settings, ORGANIZATIONS_PATH, updatedSince, CrmClient::organization),
                pages(settings, CONTACTS_PATH, updatedSince, CrmClient::contact)
        );
    }

    // Idempotency-Key — идентификатор пачки; повторную доставку того же события заказчик узнаёт по eventId события (см.
    // CrmExchangeService.deliver).
    CrmDelivery send(SourceProperties.Crm settings, UUID batchId, List<String> events) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("batchId", batchId.toString());
        ArrayNode items = body.putArray("events");
        for (String event : events) {
            try {
                items.add(objectMapper.readTree(event));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Stored exchange event cannot be read", exception);
            }
        }
        HttpRequest.Builder request = request(settings, uri(settings, EVENTS_PATH, null, 0))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", batchId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        JsonNode answer = json(execute(settings, request.build()));
        Map<String, String> rejected = new LinkedHashMap<>();
        JsonNode rejections = answer.path("rejected");
        // Отклонённые события — часть успешного ответа: пачка доставлена, отклонены отдельные события. Ответ без
        // eventId нарушает контракт.
        if (!rejections.isMissingNode() && !rejections.isNull() && !rejections.isArray()) {
            throw SourceFetchException.invalidResponse("Поле rejected в ответе CRM заказчика должно быть массивом");
        }
        for (JsonNode item : rejections) {
            String eventId = item.path("eventId").asText(null);
            if (eventId == null) {
                throw SourceFetchException.invalidResponse("В элементе rejected ответа CRM заказчика нет eventId");
            }
            rejected.put(eventId, item.path("reason").asText("причина не указана"));
        }
        return new CrmDelivery(rejected);
    }

    private List<SiteRecord> pages(
            SourceProperties.Crm settings,
            String path,
            OffsetDateTime updatedSince,
            Function<JsonNode, SiteRecord> mapper
    ) {
        List<SiteRecord> records = new ArrayList<>();
        int page = 1;
        for (int loaded = 0; ; loaded++) {
            // Предел страниц защищает от зацикленной выдачи; запуск останавливается целиком, до применения.
            if (loaded == settings.maxPages()) {
                throw SourceFetchException.invalidResponse(
                        "CRM заказчика вернула больше " + settings.maxPages() + " страниц; синхронизация остановлена без изменений"
                );
            }
            JsonNode body = json(get(settings, uri(settings, path, updatedSince, page)));
            JsonNode items = body.path("items");
            if (!body.isObject() || !items.isArray()) {
                throw SourceFetchException.invalidResponse("Ответ CRM заказчика не содержит массив items (страница " + page + ")");
            }
            items.forEach(item -> records.add(mapper.apply(item)));
            JsonNode nextPage = body.path("nextPage");
            if (nextPage.isMissingNode() || nextPage.isNull()) {
                return List.copyOf(records);
            }
            if (!nextPage.isIntegralNumber() || nextPage.intValue() <= page) {
                throw SourceFetchException.invalidResponse("Поле nextPage должно быть номером следующей страницы или null");
            }
            page = nextPage.intValue();
        }
    }

    // Поля CRM заказчика приводятся к общей форме SiteRecord: разбор, версии и применение общие с сайтом. Не объект
    // передаётся как есть и получает problem.
    private static SiteRecord organization(JsonNode item) {
        if (!item.isObject()) {
            return SiteRecord.parse(item);
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", CrmRecordApplier.ORGANIZATION);
        copy(item, "externalId", node, "externalId");
        copy(item, "updatedAt", node, "updatedAt");
        ObjectNode organization = node.putObject("organization");
        copy(item, "externalId", organization, "externalId");
        copy(item, "name", organization, "name");
        copy(item, "type", node, "organizationType");
        copy(item, "inn", node, "inn");
        copy(item, "ogrn", node, "ogrn");
        copy(item, "region", node, "region");
        return SiteRecord.parse(node);
    }

    private static SiteRecord contact(JsonNode item) {
        if (!item.isObject()) {
            return SiteRecord.parse(item);
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", CrmRecordApplier.CONTACT);
        copy(item, "externalId", node, "externalId");
        copy(item, "updatedAt", node, "updatedAt");
        copy(item, "organizationExternalId", node.putObject("organization"), "externalId");
        ObjectNode contact = node.putObject("contact");
        copy(item, "fullName", contact, "name");
        copy(item, "workEmail", contact, "email");
        copy(item, "workPhone", contact, "phone");
        copy(item, "position", contact, "position");
        copy(item, "role", node, "role");
        return SiteRecord.parse(node);
    }

    private static void copy(JsonNode from, String field, ObjectNode to, String target) {
        JsonNode value = from.path(field);
        if (!value.isMissingNode()) {
            to.set(target, value);
        }
    }

    private JsonNode json(byte[] bytes) {
        JsonNode json;
        try {
            json = objectMapper.readTree(bytes);
        } catch (IOException exception) {
            throw SourceFetchException.invalidResponse("Ответ CRM заказчика не является корректным JSON");
        }
        if (json == null || !json.isObject()) {
            throw SourceFetchException.invalidResponse("Ответ CRM заказчика не является JSON-объектом");
        }
        return json;
    }

    private byte[] get(SourceProperties.Crm settings, URI uri) {
        return execute(settings, request(settings, uri).GET().build());
    }

    private HttpRequest.Builder request(SourceProperties.Crm settings, URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(settings.readTimeout())
                .header("Accept", "application/json");
        if (settings.token() != null && !settings.token().isBlank()) {
            request.header("Authorization", "Bearer " + settings.token());
        }
        return request;
    }

    private byte[] execute(SourceProperties.Crm settings, HttpRequest request) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw SourceFetchException.unreachable(SUBJECT, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.unavailable("Синхронизация прервана");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw SourceFetchException.unauthorized("CRM заказчика отклонила токен доступа; проверьте токен CRM заказчика");
            }
            // Допустимы 200–202: приём событий может отвечать 202 («принято в обработку»).
            if (response.statusCode() < 200 || response.statusCode() > 202) {
                throw SourceFetchException.unavailable("CRM заказчика ответила HTTP " + response.statusCode());
            }
            long limit = settings.maxPageSize().toBytes();
            byte[] bytes = body.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) {
                throw SourceFetchException.invalidResponse("Ответ CRM заказчика больше " + settings.maxPageSize());
            }
            return bytes;
        } catch (IOException exception) {
            throw SourceFetchException.invalidResponse("Ответ CRM заказчика не является корректным JSON");
        }
    }

    private static URI uri(SourceProperties.Crm settings, String path, OffsetDateTime updatedSince, int page) {
        String base = settings.baseUrl().strip();
        StringBuilder uri = new StringBuilder(base.endsWith("/") ? base.substring(0, base.length() - 1) : base).append(path);
        if (page > 0) {
            uri.append("?page=").append(page);
            if (updatedSince != null) {
                uri.append("&updated_since=").append(URLEncoder.encode(updatedSince.toString(), StandardCharsets.UTF_8));
            }
        }
        try {
            URI result = URI.create(uri.toString());
            if ("http".equals(result.getScheme()) || "https".equals(result.getScheme())) {
                return result;
            }
        } catch (IllegalArgumentException exception) {
            throw SourceFetchException.unavailable("Адрес CRM заказчика некорректен");
        }
        throw SourceFetchException.unavailable("Адрес CRM заказчика должен начинаться с http:// или https://");
    }

    record CrmFetch(List<SiteRecord> organizations, List<SiteRecord> contacts) {
    }

    record CrmDelivery(Map<String, String> rejected) {
    }
}
