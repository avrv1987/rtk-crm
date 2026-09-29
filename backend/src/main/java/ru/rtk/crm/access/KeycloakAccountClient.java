package ru.rtk.crm.access;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Outcome ACCOUNT_CREATION = new Outcome(
            "Keycloak недоступен; учётная запись не создана, повторите позже",
            "; учётная запись не создана"
    );
    private static final Outcome ACCOUNT_CHANGE = new Outcome(
            "Keycloak недоступен; учётная запись не изменена, повторите позже",
            "; учётная запись не изменена"
    );
    private static final Outcome ACCOUNT_LOGOUT = new Outcome(
            "Keycloak недоступен; сеансы не завершены, повторите позже",
            "; сеансы не завершены"
    );
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
    private static final String PRIVILEGED_ROLE = "crm-privileged";
    private static final String OTP_CREDENTIAL = "otp";
    private static final long CREATION_CLOCK_SKEW_MILLIS = 60_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakAccountClient.class);

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
            logout(token, userPath, BLOCKING);
        }
    }

    public String createPartnerUser(
            String username,
            String email,
            String firstName,
            String lastName,
            String temporaryPassword
    ) {
        return createUser(username, email, firstName, lastName, temporaryPassword, PARTNER_ACCESS, new KeycloakAccountConflictException());
    }

    public String createEmployeeUser(
            String username,
            String email,
            String firstName,
            String lastName,
            String temporaryPassword
    ) {
        return createUser(username, email, firstName, lastName, temporaryPassword, ACCOUNT_CREATION, KeycloakAccountConflictException.employee());
    }

    public void enableWithTemporaryPassword(String userId, String temporaryPassword) {
        requireConfiguration(PARTNER_ACCESS);
        String token = accessToken(PARTNER_ACCESS);
        resetPassword(token, userId, temporaryPassword, PARTNER_ACCESS);
        requireSuccess(send(HttpRequest.newBuilder(uri(userPath(userId)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of(
                        "enabled", true,
                        "requiredActions", List.of(UPDATE_PASSWORD)
                )))), PARTNER_ACCESS), "включение учётной записи", PARTNER_ACCESS);
    }

    public void resetTemporaryPassword(String userId, String temporaryPassword) {
        requireConfiguration(ACCOUNT_CHANGE);
        resetPassword(accessToken(ACCOUNT_CHANGE), userId, temporaryPassword, ACCOUNT_CHANGE);
    }

    public void logout(String userId) {
        requireConfiguration(ACCOUNT_LOGOUT);
        logout(accessToken(ACCOUNT_LOGOUT), userPath(userId), ACCOUNT_LOGOUT);
    }

    public void changeEmail(String userId, String email) {
        requireConfiguration(ACCOUNT_CHANGE);
        HttpResponse<String> updated = send(HttpRequest.newBuilder(uri(userPath(userId)))
                .header("Authorization", "Bearer " + accessToken(ACCOUNT_CHANGE))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of("email", email, "emailVerified", false)))), ACCOUNT_CHANGE);
        if (updated.statusCode() == 409) {
            throw KeycloakAccountConflictException.employee();
        }
        requireSuccess(updated, "смену почты", ACCOUNT_CHANGE);
    }

    public void setPrivileged(String userId, boolean privileged) {
        requireConfiguration(ACCOUNT_CHANGE);
        String token = accessToken(ACCOUNT_CHANGE);
        String mappings = userPath(userId) + "/role-mappings/realm";
        for (JsonNode role : readArray(token, privileged ? mappings + "/available" : mappings, "чтение ролей учётной записи")) {
            if (PRIVILEGED_ROLE.equals(role.path("name").asText())) {
                String body = json(List.of(role));
                requireSuccess(send(HttpRequest.newBuilder(uri(mappings))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .method(privileged ? "POST" : "DELETE", HttpRequest.BodyPublishers.ofString(body)), ACCOUNT_CHANGE),
                        privileged ? "назначение обязательного второго фактора" : "снятие обязательного второго фактора",
                        ACCOUNT_CHANGE);
                return;
            }
        }
    }

    public int removeSecondFactor(String userId) {
        requireConfiguration(ACCOUNT_CHANGE);
        String token = accessToken(ACCOUNT_CHANGE);
        int removed = 0;
        for (JsonNode credential : readArray(token, userPath(userId) + "/credentials", "чтение способов входа")) {
            if (OTP_CREDENTIAL.equals(credential.path("type").asText())) {
                requireSuccess(send(HttpRequest.newBuilder(uri(userPath(userId) + "/credentials/" + encode(credential.path("id").asText())))
                        .header("Authorization", "Bearer " + token)
                        .DELETE(), ACCOUNT_CHANGE), "сброс второго фактора", ACCOUNT_CHANGE);
                removed++;
            }
        }
        return removed;
    }

    private JsonNode readArray(String token, String path, String operation) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .GET(), ACCOUNT_CHANGE);
        requireSuccess(response, operation, ACCOUNT_CHANGE);
        try {
            JsonNode items = objectMapper.readTree(response.body());
            if (items.isArray()) {
                return items;
            }
        } catch (IOException exception) {
            throw new AccountSyncException("Keycloak вернул ответ не в формате JSON" + ACCOUNT_CHANGE.rejectedSuffix(), exception);
        }
        throw new AccountSyncException("Keycloak вернул ответ не в формате списка" + ACCOUNT_CHANGE.rejectedSuffix());
    }

    private String createUser(
            String username,
            String email,
            String firstName,
            String lastName,
            String temporaryPassword,
            Outcome outcome,
            KeycloakAccountConflictException conflict
    ) {
        requireConfiguration(outcome);
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
        String token = accessToken(outcome);
        HttpRequest create = HttpRequest.newBuilder(uri(usersPath()))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(properties.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(json(user)))
                .build();
        long startedAt = System.currentTimeMillis();
        HttpResponse<String> created;
        try {
            created = exchange(create, outcome);
        } catch (IOException first) {
            LOGGER.warn("Keycloak user creation {}; checking whether the user was created", failure(first));
            String existing = findCreatedUser(token, username, startedAt, outcome, conflict);
            if (existing != null) {
                return existing;
            }
            try {
                created = exchange(create, outcome);
            } catch (IOException second) {
                LOGGER.warn("Keycloak user creation {} again", failure(second));
                throw new AccountSyncException(outcome.unavailable(), second);
            }
        }
        if (created.statusCode() == 409) {
            throw conflict;
        }
        requireSuccess(created, "создание учётной записи", outcome);
        String location = created.headers().firstValue("Location").orElse("");
        String userId = location.substring(location.lastIndexOf('/') + 1);
        if (userId.isBlank()) {
            throw new AccountSyncException("Keycloak не вернул идентификатор созданной учётной записи" + outcome.rejectedSuffix());
        }
        return userId;
    }

    private String findCreatedUser(
            String token,
            String username,
            long startedAt,
            Outcome outcome,
            KeycloakAccountConflictException conflict
    ) {
        HttpResponse<String> found = send(HttpRequest.newBuilder(uri(usersPath() + "?exact=true&username=" + encode(username)))
                .header("Authorization", "Bearer " + token)
                .GET(), outcome);
        requireSuccess(found, "поиск учётной записи", outcome);
        JsonNode users;
        try {
            users = objectMapper.readTree(found.body());
        } catch (IOException exception) {
            throw new AccountSyncException("Keycloak вернул список учётных записей не в формате JSON" + outcome.rejectedSuffix(), exception);
        }
        for (JsonNode candidate : users) {
            if (username.equalsIgnoreCase(candidate.path("username").asText())) {
                if (candidate.path("createdTimestamp").asLong() < startedAt - CREATION_CLOCK_SKEW_MILLIS) {
                    throw conflict;
                }
                return candidate.path("id").asText();
            }
        }
        return null;
    }

    private void resetPassword(String token, String userId, String temporaryPassword, Outcome outcome) {
        requireSuccess(send(HttpRequest.newBuilder(uri(userPath(userId) + "/reset-password"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(temporaryCredential(temporaryPassword)))), outcome),
                "выдачу временного пароля", outcome);
    }

    private void logout(String token, String userPath, Outcome outcome) {
        requireSuccess(send(HttpRequest.newBuilder(uri(userPath + "/logout"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()), outcome), "завершение сеансов учётной записи", outcome);
    }

    private void requireConfiguration(Outcome outcome) {
        if (!configured()) {
            throw new AccountSyncException(
                    "Связь с Keycloak не настроена: задайте APP_KEYCLOAK_ACCOUNT_SYNC_CLIENT_SECRET" + outcome.rejectedSuffix()
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

    private HttpResponse<String> send(HttpRequest.Builder builder, Outcome outcome) {
        HttpRequest request = builder.timeout(properties.timeout()).build();
        try {
            return exchange(request, outcome);
        } catch (IOException first) {
            LOGGER.warn("Keycloak {} {} {}; retrying once", request.method(), request.uri().getPath(), failure(first));
            try {
                return exchange(request, outcome);
            } catch (IOException second) {
                LOGGER.warn("Keycloak {} {} {} again", request.method(), request.uri().getPath(), failure(second));
                throw new AccountSyncException(outcome.unavailable(), second);
            }
        }
    }

    private HttpResponse<String> exchange(HttpRequest request, Outcome outcome) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AccountSyncException(outcome.unavailable(), exception);
        }
    }

    private static String failure(IOException exception) {
        return exception instanceof HttpTimeoutException ? "timed out" : "failed: " + exception.getClass().getSimpleName();
    }

    private static void requireSuccess(HttpResponse<String> response, String operation, Outcome outcome) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOGGER.warn("Keycloak rejected {}: HTTP {}", response.request().uri().getPath(), response.statusCode());
            throw new AccountSyncException("Keycloak отклонил " + operation + ": HTTP " + response.statusCode()
                    + outcome.rejectedSuffix());
        }
    }

    private String usersPath() {
        return "/admin/realms/" + encode(properties.realm()) + "/users";
    }

    private String userPath(String userId) {
        return usersPath() + "/" + encode(userId);
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
