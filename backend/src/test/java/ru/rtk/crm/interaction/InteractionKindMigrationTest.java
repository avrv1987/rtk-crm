package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// V58 на H2 с настоящим файлом миграции: базовый вид связан с общим шаблоном по умолчанию, все карточки получают его,
// команды со своим шаблоном по умолчанию сохраняют маршрут через командный шаблон базового вида.
class InteractionKindMigrationTest {
    private static final UUID BASE_KIND = UUID.fromString("00000000-0000-0000-0000-000000000851");

    @Test
    void existingWorksGetTheBaseKindLinkedToTheDefaultTemplate() throws IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v58-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE workflow_templates (id UUID PRIMARY KEY, team_id UUID, name VARCHAR(200) NOT NULL,"
                + " default_template BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE teams (id UUID PRIMARY KEY, default_workflow_template_id UUID REFERENCES workflow_templates(id))");
        jdbc.execute("CREATE TABLE programs (id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE products (id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE interactions (id UUID PRIMARY KEY, title VARCHAR(200) NOT NULL)");
        UUID base = UUID.randomUUID();
        UUID teamTemplate = UUID.randomUUID();
        UUID teamWithOwn = UUID.randomUUID();
        UUID teamWithout = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_templates VALUES (?, NULL, 'Базовый процесс ИТ Школы РТК', TRUE), (?, ?, 'Команда', FALSE)",
                base, teamTemplate, teamWithOwn);
        jdbc.update("INSERT INTO teams VALUES (?, ?), (?, NULL)", teamWithOwn, teamTemplate, teamWithout);
        jdbc.update("INSERT INTO interactions VALUES (?, 'Первая'), (?, 'Вторая')", UUID.randomUUID(), UUID.randomUUID());

        for (String statement : migration().split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }

        assertThat(jdbc.queryForMap("SELECT name, active, workflow_template_id, sort_order FROM interaction_kinds WHERE id = ?", BASE_KIND))
                .containsEntry("NAME", "Взаимодействие по ИТ-программе")
                .containsEntry("ACTIVE", true)
                .containsEntry("WORKFLOW_TEMPLATE_ID", base)
                .containsEntry("SORT_ORDER", 0);
        assertThat(jdbc.queryForList("SELECT DISTINCT interaction_kind_id FROM interactions", UUID.class)).containsExactly(BASE_KIND);
        assertThat(jdbc.queryForList("SELECT team_id, workflow_template_id FROM interaction_kind_team_templates"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("TEAM_ID", teamWithOwn);
                    assertThat(row).containsEntry("WORKFLOW_TEMPLATE_ID", teamTemplate);
                });
        assertThatThrownBy(() -> jdbc.update("INSERT INTO interactions (id, title) VALUES (?, 'Без вида')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO interaction_kind_rules (id, kind_id, priority) VALUES (?, ?, 10)",
                UUID.randomUUID(), BASE_KIND))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String migration() throws IOException {
        try (InputStream input = InteractionKindMigrationTest.class.getResourceAsStream("/db/migration/V58__interaction_kinds.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
