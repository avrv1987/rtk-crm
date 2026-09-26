package ru.rtk.crm.enrolment;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.enrolment")
public record EnrolmentProperties(
        boolean enabled,
        String activeKeyVersion,
        Map<String, String> keys,
        String fingerprintKey
) {
    public EnrolmentProperties {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }
}
