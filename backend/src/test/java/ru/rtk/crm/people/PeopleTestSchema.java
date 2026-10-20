package ru.rtk.crm.people;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.jdbc.core.JdbcTemplate;

// Таблицы учёта людей и часов для тестов с ручной схемой: берутся из настоящей миграции V73 без внешних ключей,
// потому что в таких тестах нет части связанных таблиц (договоров, списков LMS).
public final class PeopleTestSchema {
    private PeopleTestSchema() {
    }

    public static void create(JdbcTemplate jdbc) {
        String sql;
        try (InputStream input = PeopleTestSchema.class.getResourceAsStream("/db/migration/V73__people_and_hours.sql")) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        sql = sql.replaceAll("(?m)^--.*$", "")
                .replaceAll("REFERENCES [a-z_]+[(]id[)]( ON DELETE SET NULL)?", "")
                .replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
                .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }
}
