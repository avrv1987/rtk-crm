package ru.rtk.crm.agreement;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

public final class AgreementTestSchema {
    public static final List<String> TABLES = List.of("agreement_files", "agreement_supplements", "agreement_programs",
            "agreement_products", "agreement_directions", "agreement_interactions", "agreement_status_events");

    private AgreementTestSchema() {
    }

    public static void create(JdbcTemplate jdbc) {
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
                CREATE TABLE IF NOT EXISTS interactions (
                    id UUID PRIMARY KEY, organization_id UUID NOT NULL, title VARCHAR(200) NOT NULL, current_stage_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_stages (
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

    public static void clear(JdbcTemplate jdbc) {
        TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }
}
