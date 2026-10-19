package ru.rtk.crm.interaction;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

// Таблицы видов взаимодействия для тестов со своей схемой H2: без внешних ключей, с базовым видом из V58.
public final class InteractionKindTestSchema {
    public static final UUID BASE_KIND = UUID.fromString("00000000-0000-0000-0000-000000000851");

    private InteractionKindTestSchema() {
    }

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_kinds (
                    id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, description VARCHAR(1000),
                    active BOOLEAN DEFAULT TRUE NOT NULL, workflow_template_id UUID, sort_order INTEGER DEFAULT 0 NOT NULL,
                    version INTEGER DEFAULT 0 NOT NULL, default_kind BOOLEAN DEFAULT FALSE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_kind_team_templates (
                    kind_id UUID NOT NULL, team_id UUID NOT NULL, workflow_template_id UUID NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    PRIMARY KEY (kind_id, team_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interaction_kind_rules (
                    id UUID PRIMARY KEY, kind_id UUID NOT NULL, organization_type VARCHAR(32), program_id UUID, product_id UUID,
                    priority INTEGER DEFAULT 100 NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        StagePropertiesTestSchema.create(jdbc);
        reset(jdbc);
    }

    /** Возвращает справочник к одному базовому виду без шаблона: маршрут новых карточек — шаблон по умолчанию. */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM interaction_kind_rules");
        jdbc.update("DELETE FROM interaction_kind_team_templates");
        jdbc.update("DELETE FROM interaction_kinds WHERE id <> ?", BASE_KIND);
        jdbc.update("""
                MERGE INTO interaction_kinds (id, name, active, workflow_template_id, sort_order, version, default_kind)
                KEY (id) VALUES (?, 'Взаимодействие по ИТ-программе', TRUE, NULL, 0, 0, TRUE)
                """, BASE_KIND);
    }
}
