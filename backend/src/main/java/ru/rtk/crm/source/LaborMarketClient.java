package ru.rtk.crm.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class LaborMarketClient {
    static final String DEMAND_PATH = "/api/labor-market/demand";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final int MAX_VACANCIES = 10_000_000;
    private static final int MAX_SALARY = 100_000_000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LaborMarketClient(LaborMarketProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Токен идёт в заголовке Authorization: переход на другой адрес отдал бы его чужому хосту.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // Окно — последние historyMonths месяцев включительно: источник мог уточнить прошлые месяцы, и при отличии они
    // перезаписываются (saveSnapshots).
    Batch fetch(LaborMarketProperties settings) {
        YearMonth to = YearMonth.now(ZONE);
        return parse(settings, get(settings, uri(settings, to.minusMonths(settings.historyMonths() - 1L), to)));
    }

    Batch check(LaborMarketProperties settings) {
        YearMonth month = YearMonth.now(ZONE);
        return parse(settings, get(settings, uri(settings, month, month)));
    }

    // Предел записей защищает от разрастания ответа: при превышении запуск останавливается, ничего не сохранив.
    Batch parse(LaborMarketProperties settings, byte[] bytes) {
        JsonNode body;
        try {
            body = objectMapper.readTree(bytes);
        } catch (IOException exception) {
            throw SourceFetchException.invalidResponse("Ответ источника рынка труда не является корректным JSON");
        }
        if (body == null || !body.isObject() || !body.path("items").isArray()) {
            throw SourceFetchException.invalidResponse("Ответ источника рынка труда не содержит массив items");
        }
        if (body.path("items").size() > settings.maxItems()) {
            throw SourceFetchException.invalidResponse(
                    "Источник рынка труда вернул больше " + settings.maxItems() + " записей; синхронизация остановлена без изменений");
        }
        Map<String, LaborMarketItem> items = new LinkedHashMap<>();
        int rejected = 0;
        for (JsonNode node : body.path("items")) {
            LaborMarketItem item = item(node);
            // Повтор пары «направление и месяц» считается ошибкой записи и первую не заменяет: какая из двух верна,
            // неизвестно.
            if (item == null || items.putIfAbsent(item.key(), item) != null) {
                rejected++;
            }
        }
        return new Batch(new ArrayList<>(items.values()), rejected);
    }

    // Запись с неверным полем отбрасывается целиком: частично заполненный спрос исказил бы показатель. Зарплата
    // необязательна, но если указана, то положительная.
    private static LaborMarketItem item(JsonNode node) {
        String code = text(node, "directionCode", 100);
        String name = text(node, "directionName", 300);
        String dataSource = text(node, "dataSource", 200);
        String month = text(node, "month", 7);
        String asOf = text(node, "asOf", 10);
        if (code == null || !CODE.matcher(code).matches() || name == null || dataSource == null || month == null
                || asOf == null) {
            return null;
        }
        JsonNode vacancies = node.path("openVacancies");
        JsonNode salary = node.path("medianSalary");
        boolean salaryAbsent = salary.isMissingNode() || salary.isNull();
        if (!vacancies.isIntegralNumber() || !vacancies.canConvertToInt() || vacancies.intValue() < 0
                || vacancies.intValue() > MAX_VACANCIES
                || !salaryAbsent && (!salary.isIntegralNumber() || !salary.canConvertToInt() || salary.intValue() <= 0
                || salary.intValue() > MAX_SALARY)) {
            return null;
        }
        try {
            return new LaborMarketItem(code, name, YearMonth.parse(month), vacancies.intValue(),
                    salaryAbsent ? null : salary.intValue(), dataSource, LocalDate.parse(asOf));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String field, int limit) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().strip().length() > limit) {
            return null;
        }
        return value.textValue().strip();
    }

    private byte[] get(LaborMarketProperties settings, URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(settings.readTimeout())
                .header("Accept", "application/json")
                .GET();
        if (settings.token() != null && !settings.token().isBlank()) {
            request.header("Authorization", "Bearer " + settings.token());
        }
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw SourceFetchException.unreachable("Источник рынка труда", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.unavailable("Синхронизация прервана");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw SourceFetchException.unauthorized("Источник рынка труда отклонил токен доступа; проверьте токен");
            }
            if (response.statusCode() != 200) {
                throw SourceFetchException.unavailable("Источник рынка труда ответил HTTP " + response.statusCode());
            }
            long limit = settings.maxResponseSize().toBytes();
            byte[] bytes = body.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) {
                throw SourceFetchException.invalidResponse("Ответ источника рынка труда больше " + settings.maxResponseSize());
            }
            return bytes;
        } catch (IOException exception) {
            throw SourceFetchException.invalidResponse("Ответ источника рынка труда не является корректным JSON");
        }
    }

    private static URI uri(LaborMarketProperties settings, YearMonth from, YearMonth to) {
        String base = settings.baseUrl().strip();
        String uri = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + DEMAND_PATH + "?from=" + from + "&to=" + to;
        try {
            URI result = URI.create(uri);
            if ("http".equals(result.getScheme()) || "https".equals(result.getScheme())) {
                return result;
            }
        } catch (IllegalArgumentException exception) {
            throw SourceFetchException.unavailable("Адрес источника рынка труда некорректен");
        }
        throw SourceFetchException.unavailable("Адрес источника рынка труда должен начинаться с http:// или https://");
    }

    record Batch(List<LaborMarketItem> items, int rejected) {
    }
}
