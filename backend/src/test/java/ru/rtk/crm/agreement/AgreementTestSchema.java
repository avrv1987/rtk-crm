package ru.rtk.crm.agreement;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

public final class AgreementTestSchema {
    public static final List<String> TABLES = List.of("agreement_files", "agreement_supplements", "agreement_programs",
            "agreement_products", "agreement_directions", "agreement_interactions", "agreement_status_events");

    private AgreementTestSchema() {
    }

    public static void create(JdbcTemplate jdbc) {
        ru.rtk.crm.interaction.StagePropertiesTestSchema.create(jdbc);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_status_events (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
                    comment VARCHAR(2000), actor_profile_id UUID, interaction_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_interactions (
                    agreement_id UUID NOT NULL, interaction_id UUID NOT NULL, PRIMARY KEY (agreement_id, interaction_id)
                )
                """);
        for (String link : List.of("program", "product", "direction")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + link + "s (id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL,"
                    + " archived BOOLEAN DEFAULT FALSE NOT NULL)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS agreement_" + link + "s (agreement_id UUID NOT NULL, " + link
                    + "_id UUID NOT NULL, PRIMARY KEY (agreement_id, " + link + "_id))");
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_supplements (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, number VARCHAR(100) NOT NULL, concluded_on DATE,
                    subject VARCHAR(2000), status VARCHAR(16) NOT NULL, version INTEGER NOT NULL DEFAULT 0, created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (agreement_id, number)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_files (
                    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, supplement_id UUID, version_number INTEGER NOT NULL,
                    attachment_id UUID, storage_key UUID UNIQUE, original_name VARCHAR(255) NOT NULL, media_type VARCHAR(160) NOT NULL,
                    size_bytes BIGINT NOT NULL, checksum CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, comment VARCHAR(500),
                    created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }

    public static void createWorkTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interactions (interaction_kind_id UUID, 
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, current_stage_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_stages (requires_agreement BOOLEAN DEFAULT FALSE NOT NULL, lms_role VARCHAR(32), 
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_order INTEGER NOT NULL, name VARCHAR(200) NOT NULL,
                    template_stage_id UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attachments (
                    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, stage_id UUID NOT NULL, original_name VARCHAR(255) NOT NULL,
                    media_type VARCHAR(160) NOT NULL, size_bytes BIGINT NOT NULL, storage_key UUID NOT NULL, checksum CHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL, created_by UUID, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    deleted_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_attachments (
                    activity_id UUID NOT NULL, attachment_id UUID NOT NULL, PRIMARY KEY (activity_id, attachment_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_interactions (
                    activity_id UUID NOT NULL, interaction_id UUID NOT NULL, PRIMARY KEY (activity_id, interaction_id)
                )
                """);
    }

    // Таблицы и столбцы отчётности по соглашениям (V72) для тестов с собственной схемой H2: добавляются к уже
    // созданным таблицам договоров, если тех ещё нет — пропускаются.
    public static void createReporting(JdbcTemplate jdbc) {
        if (exists(jdbc, "AGREEMENT_ACTIVITY_KINDS")) {
            jdbc.execute("ALTER TABLE agreement_activity_kinds ADD COLUMN IF NOT EXISTS required_attributes VARCHAR(200)");
        }
        if (exists(jdbc, "AGREEMENT_ACTIVITIES")) {
            for (String column : List.of("education_level_id UUID", "participants INTEGER", "unit_id UUID", "cost NUMERIC(16, 2)",
                    "executor_id UUID", "document_note VARCHAR(500)")) {
                jdbc.execute("ALTER TABLE agreement_activities ADD COLUMN IF NOT EXISTS " + column);
            }
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_reference_items (
                    id UUID PRIMARY KEY, category VARCHAR(16) NOT NULL, name VARCHAR(200) NOT NULL, base_amount NUMERIC(16, 2),
                    sort_order INTEGER NOT NULL, archived BOOLEAN DEFAULT FALSE NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_rates (
                    id UUID PRIMARY KEY, kind_id UUID NOT NULL, unit_id UUID NOT NULL, education_level_id UUID, effective_from DATE NOT NULL,
                    rate NUMERIC(16, 2) NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_reporting_settings (
                    id SMALLINT PRIMARY KEY, period_kind VARCHAR(16) NOT NULL, academic_year_start_month INTEGER NOT NULL,
                    silence_days INTEGER, repeat_silence_days INTEGER, reminder_days INTEGER NOT NULL, threshold_enabled BOOLEAN NOT NULL,
                    threshold_percent NUMERIC(7, 3), threshold_base NUMERIC(16, 2), threshold_base_label VARCHAR(300), report_columns CLOB,
                    updated_by UUID, updated_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_reporting_deadlines (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL, due_on DATE NOT NULL, deliverable VARCHAR(500),
                    annual BOOLEAN DEFAULT FALSE NOT NULL, sort_order INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_lists (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, period_from DATE NOT NULL,
                    period_to DATE NOT NULL, period_label VARCHAR(100) NOT NULL, status VARCHAR(24) NOT NULL,
                    review_round INTEGER DEFAULT 0 NOT NULL, sent_on DATE, review_due_on DATE, decided_on DATE,
                    version INTEGER DEFAULT 0 NOT NULL, created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_list_items (
                    list_id UUID NOT NULL, activity_id UUID, item_order INTEGER NOT NULL, agreement_number VARCHAR(100) NOT NULL,
                    kind_name VARCHAR(200) NOT NULL, title VARCHAR(300) NOT NULL, education_level VARCHAR(200), executor VARCHAR(200),
                    unit VARCHAR(50), planned_volume INTEGER, participants INTEGER, cost NUMERIC(16, 2), period_start DATE,
                    period_end DATE, PRIMARY KEY (list_id, item_order)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agreement_activity_list_events (
                    id UUID PRIMARY KEY, list_id UUID NOT NULL, from_status VARCHAR(24), to_status VARCHAR(24) NOT NULL,
                    channel VARCHAR(16) NOT NULL, comment VARCHAR(2000), actor_profile_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }

    private static boolean exists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?", Integer.class, table);
        return count != null && count > 0;
    }

    public static void clear(JdbcTemplate jdbc) {
        TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }
}
