package ru.rtk.crm.interaction;

import org.springframework.jdbc.core.JdbcTemplate;

// Таблицы свойств этапа (V59) для тестов со своей схемой H2: справочник видов документов с прежними кодами и
// обязательные документы этапов. Столбцы requires_agreement и lms_role тесты объявляют в своих CREATE TABLE.
public final class StagePropertiesTestSchema {
    private StagePropertiesTestSchema() {
    }

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS document_kinds (
                    id UUID PRIMARY KEY, code VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL UNIQUE,
                    archived BOOLEAN DEFAULT FALSE NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS workflow_template_stage_documents (
                    stage_id UUID NOT NULL, kind_code VARCHAR(32) NOT NULL, PRIMARY KEY (stage_id, kind_code)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_stage_documents (
                    interaction_id UUID NOT NULL, stage_id UUID NOT NULL, kind_code VARCHAR(32) NOT NULL,
                    PRIMARY KEY (stage_id, kind_code)
                )
                """);
        String[][] kinds = {
                {"901", "CONTRACT", "Договор"}, {"902", "LICENSE_AGREEMENT", "Лицензионное соглашение"},
                {"903", "APPENDIX", "Приложение"}, {"904", "ACT", "Акт"},
                {"905", "SIGNED_SCAN", "Скан подписанного документа"}, {"906", "MATERIALS", "Учебные материалы"},
                {"907", "DOCUMENTATION", "Документация продукта"}, {"908", "CURRICULUM", "Рабочая программа"},
                {"909", "QUALIFICATION", "Документ о повышении квалификации"}, {"910", "OTHER", "Иное"}
        };
        for (String[] kind : kinds) {
            jdbc.update("MERGE INTO document_kinds (id, code, name, archived) KEY (code) VALUES (?, ?, ?, FALSE)",
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000" + kind[0]), kind[1], kind[2]);
        }
    }

    /** Отметки этапов по прежней логике — те же UPDATE, что в V59, для тестов, где этапы вставлены своим SQL. */
    public static void markLegacyTemplateStages(JdbcTemplate jdbc) {
        jdbc.update("UPDATE workflow_template_stages SET requires_agreement = TRUE"
                + " WHERE id = '00000000-0000-0000-0000-000000000816' OR LOWER(TRIM(name)) = 'подписание'");
        jdbc.update("UPDATE workflow_template_stages SET lms_role = CASE name WHEN 'Обучение преподавателей' THEN 'TEACHER_TRAINING'"
                + " WHEN 'Занятия' THEN 'CLASSES' WHEN 'Повышение квалификации' THEN 'QUALIFICATION' END"
                + " WHERE name IN ('Обучение преподавателей', 'Занятия', 'Повышение квалификации')");
    }

    public static void markLegacyStages(JdbcTemplate jdbc) {
        jdbc.update("UPDATE interaction_stages SET requires_agreement = TRUE"
                + " WHERE template_stage_id = '00000000-0000-0000-0000-000000000816' OR LOWER(TRIM(name)) = 'подписание'");
        jdbc.update("UPDATE interaction_stages SET lms_role = CASE name WHEN 'Обучение преподавателей' THEN 'TEACHER_TRAINING'"
                + " WHEN 'Занятия' THEN 'CLASSES' WHEN 'Повышение квалификации' THEN 'QUALIFICATION' END"
                + " WHERE name IN ('Обучение преподавателей', 'Занятия', 'Повышение квалификации')");
    }
}
