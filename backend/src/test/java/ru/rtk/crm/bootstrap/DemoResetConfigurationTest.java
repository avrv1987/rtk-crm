package ru.rtk.crm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DemoResetConfigurationTest {
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:demo-reset;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    private final Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/demo-reset").load();

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/demo-reset").cleanDisabled(false).load().clean();
        flyway.migrate();
        jdbcTemplate.update("INSERT INTO demo_marker (id) VALUES (1)");
    }

    @Test
    void resetCleansTheDatabaseAndMigratesItAgainWithoutEnablingCleanForTheApplication() {
        DemoResetConfiguration.migrate(flyway, properties(true, true));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo_marker", Integer.class)).isZero();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
    }

    @Test
    void ordinaryBootstrapOnlyMigratesAndKeepsData() {
        DemoResetConfiguration.migrate(flyway, properties(true, false));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo_marker", Integer.class)).isEqualTo(1);
    }

    @Test
    void resetIsRefusedForAnInstallationWithoutDemoData() {
        assertThatThrownBy(() -> DemoResetConfiguration.migrate(flyway, properties(false, true)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo_marker", Integer.class)).isEqualTo(1);
    }

    private static DemoBootstrapProperties properties(boolean demoData, boolean reset) {
        return new DemoBootstrapProperties(demoData, reset, null, List.of(), null, null);
    }
}
