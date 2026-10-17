package ru.rtk.crm.module;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.modules")
public record ModuleProperties(Toggle enrolment, Toggle partner) {
    public ModuleProperties {
        enrolment = enrolment == null ? new Toggle(false) : enrolment;
        partner = partner == null ? new Toggle(false) : partner;
    }

    public boolean enrolmentEnabled() {
        return enrolment.enabled();
    }

    public boolean partnerEnabled() {
        return partner.enabled();
    }

    public record Toggle(boolean enabled) {
    }
}
