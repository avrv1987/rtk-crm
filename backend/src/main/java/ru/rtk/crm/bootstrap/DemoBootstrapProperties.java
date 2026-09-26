package ru.rtk.crm.bootstrap;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.rtk.crm.access.UserRole;

@ConfigurationProperties("app.demo-bootstrap")
public record DemoBootstrapProperties(
        List<Identity> identities,
        List<Organization> organizations,
        List<LearningMapping> learningMappings
) {
    public DemoBootstrapProperties {
        learningMappings = learningMappings == null ? List.of() : List.copyOf(learningMappings);
    }

    public record Identity(String key, String issuer, String subject, String displayName, UserRole role, String teamKey) {
    }

    public record Organization(String name, String type, String teamKey, String ownerKey) {
    }

    public record LearningMapping(String kind, String externalKey, String organization, String program) {
    }
}
