package ru.rtk.crm.bootstrap;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.rtk.crm.access.UserRole;

@ConfigurationProperties("app.demo-bootstrap")
public record DemoBootstrapProperties(
        Boolean demoData,
        boolean reset,
        List<Team> teams,
        List<Identity> identities,
        List<Organization> organizations,
        List<LearningMapping> learningMappings
) {
    public DemoBootstrapProperties {
        demoData = demoData == null || demoData;
        teams = teams == null ? List.of() : List.copyOf(teams);
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
        learningMappings = learningMappings == null ? List.of() : List.copyOf(learningMappings);
    }

    public record Team(String key, String name) {
    }

    public record Identity(
            String key,
            String issuer,
            String subject,
            String displayName,
            UserRole role,
            String teamKey,
            Boolean enrolmentOperator,
            String organization,
            String contact
    ) {
    }

    public record Organization(String name, String type, String teamKey, String ownerKey) {
    }

    public record LearningMapping(
            String kind,
            String externalKey,
            String organization,
            String program,
            String runKind,
            Integer runStartedDaysAgo,
            Integer runEndsInDays
    ) {
    }
}
