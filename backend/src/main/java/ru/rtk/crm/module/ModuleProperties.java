package ru.rtk.crm.module;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("app.modules")
public record ModuleProperties(Toggle enrolment, Toggle partner, Toggle max, Toggle mail) {
    @ConstructorBinding
    public ModuleProperties {
        enrolment = enrolment == null ? new Toggle(false) : enrolment;
        partner = partner == null ? new Toggle(false) : partner;
        max = max == null ? new Toggle(false) : max;
        mail = mail == null ? new Toggle(false) : mail;
    }

    public ModuleProperties(Toggle enrolment, Toggle partner) {
        this(enrolment, partner, null, null);
    }

    public boolean enrolmentEnabled() {
        return enrolment.enabled();
    }

    public boolean partnerEnabled() {
        return partner.enabled();
    }

    public boolean maxEnabled() {
        return max.enabled();
    }

    public boolean mailEnabled() {
        return mail.enabled();
    }

    public record Toggle(boolean enabled) {
    }
}
