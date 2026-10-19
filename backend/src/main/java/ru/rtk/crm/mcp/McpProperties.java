package ru.rtk.crm.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mcp")
public record McpProperties(
        boolean enabled,
        String audience,
        List<String> allowedOrigins,
        int callsPerMinute,
        int listLimit,
        int eventLimit,
        int historyDays,
        String writeScope,
        Duration planTtl,
        int planPendingLimit,
        int planDailyLimit,
        Map<String, Client> clients
) {
    public static final String PATH = "/mcp";
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    // Режим ПДн по клиенту (azp токена): masked — по умолчанию, контакты с маской; none — имена и контакты скрыты.
    public boolean hidesPersonalData(String clientId) {
        Client client = clients == null || clientId == null ? null : clients.get(clientId);
        return client != null && "none".equalsIgnoreCase(client.pdMode());
    }

    public record Client(String pdMode) {
    }

    // Без явного списка допустим только собственный адрес CRM: страница чужого сайта в браузере пользователя
    // не достучится до /mcp через подмену DNS (требование Streamable HTTP проверять Origin).
    public List<String> origins(String publicBaseUrl) {
        return allowedOrigins == null || allowedOrigins.isEmpty() ? List.of(publicBaseUrl) : allowedOrigins;
    }
}
