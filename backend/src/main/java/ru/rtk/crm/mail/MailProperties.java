package ru.rtk.crm.mail;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mail")
@MailEnabled
public record MailProperties(
        String publicBaseUrl,
        boolean jobsEnabled,
        Duration messageTtl,
        Duration invitationTtl,
        Duration retention,
        Duration connectTimeout
) {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    public MailProperties {
        messageTtl = messageTtl == null ? Duration.ofHours(48) : messageTtl;
        invitationTtl = invitationTtl == null ? Duration.ofHours(72) : invitationTtl;
        retention = retention == null ? Duration.ofDays(30) : retention;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
    }

    public String crmUrl(String hash) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.strip().replaceAll("/+$", "");
        return base + "/" + hash;
    }
}
