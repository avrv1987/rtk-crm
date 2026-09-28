package ru.rtk.crm.access;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class KeycloakAccountClient {
    private static final Outcome BLOCKING = new Outcome(
            "Keycloak недоступен; блокировка в CRM действует, повторите синхронизацию учётной записи позже",
            "; блокировка в CRM действует"
    );
    private static final Outcome PARTNER_ACCESS = new Outcome(
            "Keycloak недоступен; доступ в кабинет вуза не открыт, повторите позже",
            "; доступ в кабинет вуза не открыт"
    );
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

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
        String token = accessToken(BLOCKING);
        String userPath = userPath(userId);
        HttpResponse<String> update = send(HttpRequest.newBuilder(uri(userPath))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of("enabled", enabled)))), BLOCKING);
        if (update.statusCode() == 404 && !enabled) {
            return;
        }
        requireSuccess(update, enabled ? "включение учётной записи" : "отключение учётной записи", BLOCKING);
        if (!enabled) {
            requireSuccess(send(HttpRequest.newBuilder(uri(userPath + "/logout"))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody()), BLOCKING), "завершение сеансов учётной записи", BLOCKING);
        }
    }

    public String createPartnerUser(
            String username,
            String email,
            String firstName,
            String lastName,
            String temporaryPassword
    ) {
        requirePartnerConfiguration();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        if (email != null) {
            user.put("email", email);
            user.put("emailVerified", false);
        }
        user.put("firstName", firstName);
        user.put("lastName", lastName);
        user.put("enabled", true);
        user.put("requiredActions", List.of(UPDATE_PASSWORD));
        user.put("credentials", List.of(temporaryCredential(temporaryPassword)));
        HttpResponse<String> created = send(HttpRequest.newBuilder(uri("/admin/realms/" + encode(properties.realm()) + "/users"))
                .header("Authorization", "Bearer " + accessToken(PARTNER_ACCESS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(user))), PARTNER_ACCESS);
        if (created.statusCode() == 409) {
            throw new KeycloakAccountConflictException();
        }
        requireSuccess(created, "создание учётной записи", PARTNER_ACCESS);
        String location = created.headers().firstValue("Location").orElse("");
        String userId = location.substring(location.lastIndexOf('/') + 1);
        if (userId.isBlank()) {
            throw new AccountSyncException("Keycloak не вернул идентификатор созданной учётной записи; доступ в кабинет вуза не открыт");
        }
        return userId;
    }

    public void enableWithTemporaryPassword(String userId, String temporaryPassword) {
        requirePartnerConfiguration();
        String token = accessToken(PARTNER_ACCESS);
        requireSuccess(send(HttpRequest.newBuilder(uri(userPath(userId) + "/reset-password"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(temporaryCredential(temporaryPassword)))), PARTNER_ACCESS),
                "выдачу временного пароля", PARTNER_ACCESS);
        requireSuccess(send(HttpRequest.newBuilder(uri(userPath(userId)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of(
                        "enabled", true,
                        "requiredActions", List.of(UPDATE_PASSWORD)
                )))), PARTNER_ACCESS), "включение учётной записи", PARTNER_ACCESS);
    }

    private void requirePartnerConfiguration() {
        if (!configured()) {
            throw new AccountSyncException(
                    "Связь с Keycloak не настроена: задайте APP_KEYCLOAK_ACCOUNT_SYNC_CLIENT_SECRET; доступ в кабинет вуза не открыт"
            );
        }
    }

    private static Map<String, Object> temporaryCredential(String password) {
        return Map.of("type", "password", "value", password, "temporary", true);
    }

    private String accessToken(Outcome outcome) {
        String form = "grant_type=client_credentials&client_id=" + encode(properties.clientId())
                + "&client_secret=" + encode(properties.clientSecret());
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(
                        "/realms/" + encode(properties.realm()) + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), outcome);
        requireSuccess(response, "выдачу токена сервисному клиенту", outcome);
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

    private HttpResponse<String> send(HttpRequest.Builder request, Outcome outcome) {
        try {
            return httpClient.send(request.timeout(properties.timeout()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new AccountSyncException(outcome.unavailable(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AccountSyncException(outcome.unavailable(), exception);
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String operation, Outcome outcome) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AccountSyncException("Keycloak отклонил " + operation + ": HTTP " + response.statusCode()
                    + outcome.rejectedSuffix());
        }
    }

    private String userPath(String userId) {
        return "/admin/realms/" + encode(properties.realm()) + "/users/" + encode(userId);
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

    private record Outcome(String unavailable, String rejectedSuffix) {
    }
}
