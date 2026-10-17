package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceCodeMigrationTest {

    @Test
    void migrationsKeepTheSourceCodeCheckWithEverySourceCode() throws IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sources-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE sources (
                    code VARCHAR(16) PRIMARY KEY CONSTRAINT sources_code_check CHECK (code IN ('WEBSITE', 'MOODLE'))
                )
                """);

        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        Arrays.sort(migrations, Comparator.comparingInt(SourceCodeMigrationTest::version));
        for (Resource migration : migrations) {
            for (String statement : migration.getContentAsString(StandardCharsets.UTF_8).split(";")) {
                String sql = statement.strip();
                if (sql.startsWith("ALTER TABLE sources ") || sql.startsWith("INSERT INTO sources ")) {
                    jdbc.execute(sql);
                }
            }
        }

        assertThat(jdbc.queryForList("SELECT code FROM sources", String.class))
                .containsExactlyInAnyOrder(Arrays.stream(SourceCode.values()).map(Enum::name).toArray(String[]::new));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sources (code) VALUES ('UNKNOWN')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static int version(Resource migration) {
        String name = migration.getFilename();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
