package ru.rtk.crm.access;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.account-sync")
public record AccountSyncProperties(String baseUrl, String realm, String clientId, String clientSecret, Duration timeout) {
    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
