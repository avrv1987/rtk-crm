package ru.rtk.crm.bootstrap;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("demo-bootstrap")
public class DemoResetConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoResetConfiguration.class);

    @Bean
    FlywayMigrationStrategy demoResetMigrationStrategy(DemoBootstrapProperties properties) {
        return flyway -> migrate(flyway, properties);
    }

    static void migrate(Flyway flyway, DemoBootstrapProperties properties) {
        if (properties.reset()) {
            if (!properties.demoData()) {
                throw new IllegalStateException("Demo data reset is not available for an installation without demo data");
            }
            Flyway.configure().configuration(flyway.getConfiguration()).cleanDisabled(false).load().clean();
            LOGGER.info("CRM database cleaned for demo data reset");
        }
        flyway.migrate();
    }
}
