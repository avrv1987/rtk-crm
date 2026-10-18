package ru.rtk.crm.max;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.max")
@MaxEnabled
public record MaxProperties(
        URI apiBaseUrl,
        String publicBaseUrl,
        Path trustDir,
        Duration connectTimeout,
        Duration readTimeout,
        Duration linkCodeTtl,
        Duration initDataMaxAge,
        Duration sessionIdle,
        Duration sessionLifetime,
        Duration messageTtl,
        int quietBeforeHour,
        int quietAfterHour,
        boolean jobsEnabled,
        Duration inboxRetention,
        Duration outboxRetention
) {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    public MaxProperties {
        apiBaseUrl = apiBaseUrl == null ? URI.create("https://platform-api2.max.ru") : apiBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        linkCodeTtl = linkCodeTtl == null ? Duration.ofMinutes(10) : linkCodeTtl;
        initDataMaxAge = initDataMaxAge == null ? Duration.ofHours(1) : initDataMaxAge;
        sessionIdle = sessionIdle == null ? Duration.ofMinutes(30) : sessionIdle;
        sessionLifetime = sessionLifetime == null ? Duration.ofHours(4) : sessionLifetime;
        messageTtl = messageTtl == null ? Duration.ofHours(24) : messageTtl;
        quietBeforeHour = quietBeforeHour == 0 ? 9 : quietBeforeHour;
        quietAfterHour = quietAfterHour == 0 ? 20 : quietAfterHour;
        inboxRetention = inboxRetention == null ? Duration.ofDays(7) : inboxRetention;
        outboxRetention = outboxRetention == null ? Duration.ofDays(30) : outboxRetention;
    }

    // Адрес вебхука строится от публичного адреса CRM: MAX принимает только HTTPS на порту 443, а nginx проксирует в
    // backend только пути под /api/.
    public String webhookUrl() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return null;
        }
        return publicBaseUrl.strip().replaceAll("/+$", "") + "/api/max/webhook";
    }

    public String crmUrl(String hash) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.strip().replaceAll("/+$", "");
        return base + "/" + hash;
    }
}
