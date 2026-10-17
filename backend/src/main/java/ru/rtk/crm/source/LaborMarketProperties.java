package ru.rtk.crm.source;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.labor-market")
public record LaborMarketProperties(
        String baseUrl,
        String token,
        Duration connectTimeout,
        Duration readTimeout,
        DataSize maxResponseSize,
        int maxItems,
        int historyMonths
) {
    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    LaborMarketProperties with(String newBaseUrl, String newToken) {
        return new LaborMarketProperties(newBaseUrl, newToken, connectTimeout, readTimeout, maxResponseSize, maxItems,
                historyMonths);
    }
}
