package ru.rtk.crm.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeycloakAccountClientTest {
    private static final String USER = "7d7c2f0e-1111-4c1a-9a55-000000000001";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int userStatus = 204;
    private volatile String slowPath;
    private final AtomicInteger slowResponses = new AtomicInteger();
    private volatile String lookup = "[]";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void blockingDisablesTheUserAndEndsItsSessionsWithAServiceClientToken() {
        client("sync-secret").setEnabled(USER, false);

        assertThat(requests).containsExactly(
                "POST /realms/rtk-crm/protocol/openid-connect/token grant_type=client_credentials&client_id=crm-account-sync&client_secret=sync-secret",
                "PUT /admin/realms/rtk-crm/users/" + USER + " Bearer service-token {\"enabled\":false}",
                "POST /admin/realms/rtk-crm/users/" + USER + "/logout Bearer service-token "
        );
    }

    @Test
    void unblockingEnablesTheUserWithoutTouchingSessions() {
        client("sync-secret").setEnabled(USER, true);

        assertThat(requests).hasSize(2).last().isEqualTo(
                "PUT /admin/realms/rtk-crm/users/" + USER + " Bearer service-token {\"enabled\":true}"
        );
    }

    @Test
    void userAlreadyDeletedInKeycloakCountsAsDisabled() {
        userStatus = 404;

        client("sync-secret").setEnabled(USER, false);

        assertThat(requests).hasSize(2);
    }

    @Test
    void rejectionAndMissingConfigurationAreReportedWithoutLeakingTheSecret() {
        userStatus = 403;

        assertThatThrownBy(() -> client("sync-secret").setEnabled(USER, false))
                .isInstanceOf(AccountSyncException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("блокировка в CRM действует")
                .hasMessageNotContaining("sync-secret");
        assertThatThrownBy(() -> client("").setEnabled(USER, false))
                .isInstanceOf(AccountSyncException.class)
                .hasMessageContaining("APP_KEYCLOAK_ACCOUNT_SYNC_CLIENT_SECRET");
    }

    @Test
    void partnerAccountGetsTemporaryPasswordAndMustChangeItAtFirstSignIn() {
        userStatus = 201;

        String userId = client("sync-secret").createPartnerUser(
                "rector@example.test", "rector@example.test", "Проректор", "Университет А", "TemporaryPass42"
        );

        assertThat(userId).isEqualTo("new-user-id");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1))
                .startsWith("POST /admin/realms/rtk-crm/users Bearer service-token {\"username\":\"rector@example.test\"")
                .contains("\"enabled\":true", "\"requiredActions\":[\"UPDATE_PASSWORD\"]", "\"temporary\":true");

        userStatus = 409;
        assertThatThrownBy(() -> client("sync-secret").createPartnerUser("rector@example.test", null, "Проректор", "Вуз", "x"))
                .isInstanceOf(KeycloakAccountConflictException.class);
    }

    @Test
    void reopenedPartnerAccountGetsNewTemporaryPasswordAndIsEnabled() {
        client("sync-secret").enableWithTemporaryPassword(USER, "TemporaryPass42");

        assertThat(requests).hasSize(3);
        assertThat(requests.get(1)).startsWith("PUT /admin/realms/rtk-crm/users/" + USER + "/reset-password ")
                .contains("\"temporary\":true");
        assertThat(requests.get(2)).startsWith("PUT /admin/realms/rtk-crm/users/" + USER + " ")
                .contains("\"enabled\":true", "UPDATE_PASSWORD");

        assertThatThrownBy(() -> client("").enableWithTemporaryPassword(USER, "TemporaryPass42"))
                .isInstanceOf(AccountSyncException.class)
                .hasMessageContaining("доступ в кабинет вуза не открыт");
    }

    @Test
    void employeeAccountIsCreatedWithTemporaryPasswordAndConflictIsNotAboutPartners() {
        userStatus = 201;

        String userId = client("sync-secret").createEmployeeUser(
                "ivanov", "ivanov@example.test", "Иван", "Иванов", "TemporaryPass42"
        );

        assertThat(userId).isEqualTo("new-user-id");
        assertThat(requests.get(1))
                .startsWith("POST /admin/realms/rtk-crm/users Bearer service-token {\"username\":\"ivanov\"")
                .contains("\"email\":\"ivanov@example.test\"", "\"requiredActions\":[\"UPDATE_PASSWORD\"]", "\"temporary\":true");

        userStatus = 409;
        assertThatThrownBy(() -> client("sync-secret").createEmployeeUser("ivanov", "ivanov@example.test", "Иван", "Иванов", "x"))
                .isInstanceOf(KeycloakAccountConflictException.class)
                .hasMessageNotContaining("контакта")
                .extracting(exception -> ((KeycloakAccountConflictException) exception).code())
                .isEqualTo("ACCOUNT_CONFLICT");
        assertThatThrownBy(() -> client("").createEmployeeUser("ivanov", "ivanov@example.test", "Иван", "Иванов", "x"))
                .isInstanceOf(AccountSyncException.class)
                .hasMessageContaining("учётная запись не создана");
    }

    @Test
    void passwordResetDoesNotEnableABlockedAccount() {
        client("sync-secret").resetTemporaryPassword(USER, "TemporaryPass42");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).startsWith("PUT /admin/realms/rtk-crm/users/" + USER + "/reset-password ")
                .contains("\"temporary\":true", "TemporaryPass42");
    }

    @Test
    void logoutEndsAllSessionsOfTheUser() {
        client("sync-secret").logout(USER);

        assertThat(requests).hasSize(2).last().isEqualTo("POST /admin/realms/rtk-crm/users/" + USER + "/logout Bearer service-token ");
    }

    @Test
    void emailChangeLeavesTheLoginAndReportsConflicts() {
        client("sync-secret").changeEmail(USER, "new@example.test");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).startsWith("PUT /admin/realms/rtk-crm/users/" + USER + " Bearer service-token {")
                .contains("\"email\":\"new@example.test\"", "\"emailVerified\":false")
                .doesNotContain("username");

        userStatus = 409;
        assertThatThrownBy(() -> client("sync-secret").changeEmail(USER, "taken@example.test"))
                .isInstanceOf(KeycloakAccountConflictException.class);
        userStatus = 500;
        assertThatThrownBy(() -> client("sync-secret").changeEmail(USER, "new@example.test"))
                .isInstanceOf(AccountSyncException.class)
                .hasMessageContaining("учётная запись не изменена");
    }

    @Test
    void slowFirstTokenRequestIsRetriedOnceAndThenTheOperationSucceeds() {
        slowPath = "/realms/rtk-crm/protocol/openid-connect/token";
        slowResponses.set(1);

        client("sync-secret", Duration.ofMillis(500)).logout(USER);

        assertThat(requests).hasSize(3);
        assertThat(requests.get(0)).startsWith("POST /realms/rtk-crm/protocol/openid-connect/token");
        assertThat(requests.get(1)).startsWith("POST /realms/rtk-crm/protocol/openid-connect/token");
        assertThat(requests.get(2)).isEqualTo("POST /admin/realms/rtk-crm/users/" + USER + "/logout Bearer service-token ");
    }

    @Test
    void idempotentRequestIsRetriedOnlyOnceAndThenReportedAsUnavailable() {
        slowPath = "/admin/realms/rtk-crm/users/" + USER + "/reset-password";
        slowResponses.set(2);

        assertThatThrownBy(() -> client("sync-secret", Duration.ofMillis(500)).resetTemporaryPassword(USER, "TemporaryPass42"))
                .isInstanceOf(AccountSyncException.class)
                .hasMessage("Keycloak недоступен; учётная запись не изменена, повторите позже");
        assertThat(requests).filteredOn(request -> request.contains("/reset-password")).hasSize(2);
    }

    @Test
    void timedOutCreationIsNotRepeatedWhenKeycloakAlreadyCreatedTheUser() {
        slowPath = "/admin/realms/rtk-crm/users";
        slowResponses.set(1);
        userStatus = 201;
        lookup = "[{\"id\":\"created-id\",\"username\":\"ivanov\",\"createdTimestamp\":" + System.currentTimeMillis() + "}]";

        String userId = client("sync-secret", Duration.ofMillis(500))
                .createEmployeeUser("ivanov", "ivanov@example.test", "Иван", "Иванов", "TemporaryPass42");

        assertThat(userId).isEqualTo("created-id");
        assertThat(requests).filteredOn(request -> request.startsWith("POST /admin/realms/rtk-crm/users ")).hasSize(1);
        assertThat(requests).anyMatch(request -> request.startsWith("GET /admin/realms/rtk-crm/users "));
    }

    @Test
    void timedOutCreationIsRepeatedOnceWhenTheUserWasNotCreated() {
        slowPath = "/admin/realms/rtk-crm/users";
        slowResponses.set(1);
        userStatus = 201;

        String userId = client("sync-secret", Duration.ofMillis(500))
                .createEmployeeUser("ivanov", "ivanov@example.test", "Иван", "Иванов", "TemporaryPass42");

        assertThat(userId).isEqualTo("new-user-id");
        assertThat(requests).filteredOn(request -> request.startsWith("POST /admin/realms/rtk-crm/users ")).hasSize(2);
    }

    @Test
    void timedOutCreationFindingAnOlderUserIsAConflictNotAnAdoption() {
        slowPath = "/admin/realms/rtk-crm/users";
        slowResponses.set(1);
        lookup = "[{\"id\":\"old-id\",\"username\":\"ivanov\",\"createdTimestamp\":1000}]";

        assertThatThrownBy(() -> client("sync-secret", Duration.ofMillis(500))
                .createEmployeeUser("ivanov", "ivanov@example.test", "Иван", "Иванов", "TemporaryPass42"))
                .isInstanceOf(KeycloakAccountConflictException.class);
        assertThat(requests).filteredOn(request -> request.startsWith("POST /admin/realms/rtk-crm/users ")).hasSize(1);
    }

    @Test
    void unreachableKeycloakIsReportedAsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client("sync-secret").setEnabled(USER, false))
                .isInstanceOf(AccountSyncException.class)
                .hasMessageContaining("Keycloak недоступен");
    }

    private KeycloakAccountClient client(String secret) {
        return client(secret, Duration.ofSeconds(2));
    }

    private KeycloakAccountClient client(String secret, Duration timeout) {
        AccountSyncProperties properties = new AccountSyncProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", "rtk-crm", "crm-account-sync", secret, timeout
        );
        return new KeycloakAccountClient(properties, new ObjectMapper());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + path + " "
                + (authorization == null ? "" : authorization + " ") + body);
        if (path.equals(slowPath) && slowResponses.getAndDecrement() > 0) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            byte[] users = lookup.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, users.length);
            exchange.getResponseBody().write(users);
        } else if (path.endsWith("/token")) {
            byte[] token = "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, token.length);
            exchange.getResponseBody().write(token);
        } else {
            if (path.endsWith("/users")) {
                exchange.getResponseHeaders().add("Location", "http://keycloak.test/admin/realms/rtk-crm/users/new-user-id");
            }
            exchange.sendResponseHeaders(path.endsWith("/logout") ? 204 : userStatus, -1);
        }
        exchange.close();
    }
}
