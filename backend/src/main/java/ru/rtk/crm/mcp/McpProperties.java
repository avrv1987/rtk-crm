package ru.rtk.crm.mcp;

import java.time.Duration;
import java.util.List;

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
        int planDailyLimit
) {
    public static final String PATH = "/mcp";
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    // Без явного списка допустим только собственный адрес CRM: страница чужого сайта в браузере пользователя
    // не достучится до /mcp через подмену DNS (требование Streamable HTTP проверять Origin).
    public List<String> origins(String publicBaseUrl) {
        return allowedOrigins == null || allowedOrigins.isEmpty() ? List.of(publicBaseUrl) : allowedOrigins;
    }
}
