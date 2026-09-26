package ru.rtk.crm.access;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class KeycloakAccountClient {
    private static final String UNAVAILABLE =
            "Keycloak недоступен; блокировка в CRM действует, повторите синхронизацию учётной записи позже";

    private final AccountSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public KeycloakAccountClient(AccountSyncProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean configured() {
        return properties.configured();
    }

    public void setEnabled(String userId, boolean enabled) {
        if (!configured()) {
            throw AccountSyncException.notConfigured();
        }
        String token = accessToken();
        String userPath = "/admin/realms/" + encode(properties.realm()) + "/users/" + encode(userId);
        HttpResponse<String> update = send(HttpRequest.newBuilder(uri(userPath))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of("enabled", enabled)))));
        if (update.statusCode() == 404 && !enabled) {
            return;
        }
        requireSuccess(update, enabled ? "включение учётной записи" : "отключение учётной записи");
        if (!enabled) {
            requireSuccess(send(HttpRequest.newBuilder(uri(userPath + "/logout"))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())), "завершение сеансов учётной записи");
        }
    }

    private String accessToken() {
        String form = "grant_type=client_credentials&client_id=" + encode(properties.clientId())
                + "&client_secret=" + encode(properties.clientSecret());
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(
                        "/realms/" + encode(properties.realm()) + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        requireSuccess(response, "выдачу токена сервисному клиенту");
        JsonNode token;
        try {
            token = objectMapper.readTree(response.body()).path("access_token");
        } catch (IOException exception) {
            throw new AccountSyncException("Keycloak вернул ответ на запрос токена не в формате JSON", exception);
        }
        if (!token.isTextual() || token.asText().isBlank()) {
            throw new AccountSyncException("Keycloak не выдал токен сервисному клиенту");
        }
        return token.asText();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return httpClient.send(request.timeout(properties.timeout()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new AccountSyncException(UNAVAILABLE, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AccountSyncException(UNAVAILABLE, exception);
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AccountSyncException("Keycloak отклонил " + operation + ": HTTP " + response.statusCode()
                    + "; блокировка в CRM действует");
        }
    }

    private URI uri(String path) {
        String base = properties.baseUrl().endsWith("/")
                ? properties.baseUrl().substring(0, properties.baseUrl().length() - 1)
                : properties.baseUrl();
        return URI.create(base + path);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Keycloak request body cannot be written", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
