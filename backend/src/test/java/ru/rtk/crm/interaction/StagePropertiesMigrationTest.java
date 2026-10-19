package ru.rtk.crm.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// V59 на H2 настоящим файлом миграции, «до и после»: этапы, которые прежняя логика считала этапом подписания (по
// идентификатору этапа базового шаблона или по названию «Подписание» без учёта регистра) и этапами LMS (точное
// название), после миграции отмечены ровно теми же свойствами — в шаблонах и в карточках, базовых и своих. Версии,
// сохранённые до V59, читаются с теми же отметками.
class StagePropertiesMigrationTest {
    private static final UUID BASE_SIGNING = UUID.fromString("00000000-0000-0000-0000-000000000816");
    private static final Map<String, String> LEGACY_LMS = Map.of(
            "Обучение преподавателей", "TEACHER_TRAINING", "Занятия", "CLASSES", "Повышение квалификации", "QUALIFICATION");

    @Test
    void migrationMarksExactlyTheStagesTheOldRulesFound() throws IOException {
        JdbcTemplate jdbc = database();
        UUID base = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_template_stages (id, template_id, stage_order, name) VALUES"
                        + " (?, ?, 0, 'Обмен документами'), (?, ?, 1, 'Подписание'), (?, ?, 2, 'Занятия'), (?, ?, 3, 'Обучение преподавателей'),"
                        + " (?, ?, 0, ' подписание '), (?, ?, 1, 'Занятия со студентами'), (?, ?, 2, 'Повышение квалификации')",
                UUID.randomUUID(), base, BASE_SIGNING, base, UUID.randomUUID(), base, UUID.randomUUID(), base,
                UUID.randomUUID(), team, UUID.randomUUID(), team, UUID.randomUUID(), team);
        UUID baseCard = UUID.randomUUID();
        UUID renamedCard = UUID.randomUUID();
        jdbc.update("INSERT INTO interaction_stages (id, interaction_id, stage_order, name, template_stage_id) VALUES"
                        + " (?, ?, 0, 'Подписание', ?), (?, ?, 1, 'Занятия', NULL),"
                        + " (?, ?, 0, 'Подписание договора', ?), (?, ?, 1, 'ПОДПИСАНИЕ', NULL), (?, ?, 2, 'Учёба', NULL),"
                        + " (?, ?, 3, 'Повышение квалификации', NULL)",
                UUID.randomUUID(), baseCard, BASE_SIGNING, UUID.randomUUID(), baseCard,
                UUID.randomUUID(), renamedCard, BASE_SIGNING, UUID.randomUUID(), renamedCard, UUID.randomUUID(), renamedCard,
                UUID.randomUUID(), renamedCard);
        // «До»: отметки по прежним правилам, посчитанные тем же способом, что прежние AgreementStages и запросы LMS.
        Map<UUID, String> templateBefore = legacy(jdbc.queryForList("SELECT id, name, id AS template_stage_id FROM workflow_template_stages"));
        Map<UUID, String> cardBefore = legacy(jdbc.queryForList("SELECT id, name, template_stage_id FROM interaction_stages"));

        execute(jdbc, migration());

        assertThat(marks(jdbc, "workflow_template_stages")).isEqualTo(templateBefore);
        assertThat(marks(jdbc, "interaction_stages")).isEqualTo(cardBefore);
        assertThat(cardBefore.values()).contains("договор|", "договор|", "договор|", "|CLASSES", "|QUALIFICATION");
        assertThat(jdbc.queryForList("SELECT code FROM document_kinds ORDER BY code", String.class)).containsExactly(
                "ACT", "APPENDIX", "CONTRACT", "CURRICULUM", "DOCUMENTATION", "LICENSE_AGREEMENT", "MATERIALS", "OTHER", "QUALIFICATION",
                "SIGNED_SCAN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_name_locks WHERE entity_type = 'DOCUMENT_KIND'", Integer.class)).isOne();
        jdbc.update("INSERT INTO report_jobs (id, group_by, series_by) VALUES (?, 'MILESTONE', 'KIND')", UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attachments (id, kind) VALUES (?, 'NO_SUCH')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE interaction_stages SET lms_role = 'LESSONS'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void versionsSavedBeforeTheMigrationReadWithTheSameMarks() {
        String legacy = """
                {"stages":[{"id":"%s","name":"Подписание договора","order":0,"optional":false},
                           {"id":"%s","name":"Занятия","order":1,"optional":false},
                           {"id":"%s","name":"Переговоры","order":2,"optional":true}],"transitions":[]}
                """.formatted(BASE_SIGNING, UUID.randomUUID(), UUID.randomUUID());
        WorkflowRoute route = WorkflowRoute.read(new ObjectMapper(), legacy);
        assertThat(route.stages()).extracting(WorkflowTemplateStage::requiresAgreement).containsExactly(true, false, false);
        assertThat(route.stages()).extracting(WorkflowTemplateStage::lmsRole).containsExactly(null, StageLmsRole.CLASSES, null);
        assertThat(route.stages()).allSatisfy(stage -> assertThat(stage.documentKinds()).isEmpty());

        String saved = new WorkflowRoute(List.of(new WorkflowTemplateStage(UUID.randomUUID(), "Подписание", 0, false, false, null,
                List.of("ACT"))), List.of()).write(new ObjectMapper());
        assertThat(WorkflowRoute.read(new ObjectMapper(), saved).stages().getFirst())
                .as("сохранённое после V59 «нет договора» не заменяется прежним правилом")
                .satisfies(stage -> {
                    assertThat(stage.requiresAgreement()).isFalse();
                    assertThat(stage.documentKinds()).containsExactly("ACT");
                });
    }

    private static Map<UUID, String> legacy(List<Map<String, Object>> rows) {
        return rows.stream().collect(Collectors.toMap(row -> (UUID) row.get("ID"), row -> {
            String name = (String) row.get("NAME");
            boolean signing = BASE_SIGNING.equals(row.get("TEMPLATE_STAGE_ID")) || "подписание".equals(name.strip().toLowerCase(Locale.ROOT));
            return (signing ? "договор" : "") + "|" + LEGACY_LMS.getOrDefault(name, "");
        }));
    }

    private static Map<UUID, String> marks(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT id, requires_agreement, lms_role FROM " + table).stream().collect(Collectors.toMap(
                row -> (UUID) row.get("ID"),
                row -> (Boolean.TRUE.equals(row.get("REQUIRES_AGREEMENT")) ? "договор" : "") + "|"
                        + (row.get("LMS_ROLE") == null ? "" : row.get("LMS_ROLE"))));
    }

    // Таблицы до V59 — в объёме, который трогает миграция, с теми же именами ограничений, что у PostgreSQL.
    private static JdbcTemplate database() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v59-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE workflow_template_stages (id UUID PRIMARY KEY, template_id UUID NOT NULL, stage_order INTEGER NOT NULL,"
                + " name VARCHAR(200) NOT NULL)");
        jdbc.execute("CREATE TABLE interaction_stages (id UUID NOT NULL, interaction_id UUID NOT NULL, stage_order INTEGER NOT NULL,"
                + " name VARCHAR(200) NOT NULL, template_stage_id UUID, PRIMARY KEY (id), CONSTRAINT interaction_stages_id_key UNIQUE (id, interaction_id))");
        jdbc.execute("CREATE TABLE attachments (id UUID PRIMARY KEY, kind VARCHAR(32) DEFAULT 'OTHER' NOT NULL,"
                + " CONSTRAINT attachments_kind_check CHECK (kind IN ('CONTRACT', 'OTHER')))");
        jdbc.execute("CREATE TABLE catalog_change_events (id UUID PRIMARY KEY, entity_type VARCHAR(16) NOT NULL,"
                + " CONSTRAINT catalog_change_events_entity_type_check CHECK (entity_type IN ('DIRECTION')))");
        jdbc.execute("CREATE TABLE catalog_name_locks (entity_type VARCHAR(16) PRIMARY KEY,"
                + " CONSTRAINT catalog_name_locks_entity_type_check CHECK (entity_type IN ('DIRECTION')))");
        jdbc.execute("CREATE TABLE report_jobs (id UUID PRIMARY KEY, group_by VARCHAR(16), series_by VARCHAR(16),"
                + " CONSTRAINT report_jobs_group_by_check CHECK (group_by IN ('STAGE')),"
                + " CONSTRAINT report_jobs_series_by_check CHECK (series_by IN ('STAGE')))");
        return jdbc;
    }

    private static void execute(JdbcTemplate jdbc, String sql) {
        String withoutComments = sql.lines().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        for (String statement : withoutComments.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    private static String migration() throws IOException {
        try (InputStream input = StagePropertiesMigrationTest.class.getResourceAsStream("/db/migration/V59__stage_properties.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
