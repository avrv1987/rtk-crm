CREATE TABLE interaction_kinds (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    workflow_template_id UUID REFERENCES workflow_templates(id) ON DELETE SET NULL,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT interaction_kinds_name_key UNIQUE (name)
);

CREATE TABLE interaction_kind_team_templates (
    kind_id UUID NOT NULL REFERENCES interaction_kinds(id) ON DELETE CASCADE,
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    workflow_template_id UUID NOT NULL REFERENCES workflow_templates(id) ON DELETE CASCADE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (kind_id, team_id)
);

CREATE TABLE interaction_kind_rules (
    id UUID PRIMARY KEY,
    kind_id UUID NOT NULL REFERENCES interaction_kinds(id) ON DELETE CASCADE,
    organization_type VARCHAR(32)
        CHECK (organization_type IN ('UNIVERSITY', 'SCHOOL', 'COLLEGE', 'OPEN_ENROLLMENT')),
    program_id UUID REFERENCES programs(id) ON DELETE CASCADE,
    product_id UUID REFERENCES products(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL DEFAULT 100 CHECK (priority BETWEEN 1 AND 1000),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (organization_type IS NOT NULL OR program_id IS NOT NULL OR product_id IS NOT NULL)
);

CREATE INDEX interaction_kind_rules_kind_idx ON interaction_kind_rules (kind_id);

-- Базовый вид связывается с текущим общим шаблоном по умолчанию (в новой установке это «Базовый процесс ИТ Школы РТК»).
INSERT INTO interaction_kinds (id, name, description, active, workflow_template_id, sort_order, version)
VALUES (
    '00000000-0000-0000-0000-000000000851',
    'Взаимодействие по ИТ-программе',
    'Запуск и сопровождение ИТ-программы в вузе: от поиска контакта до занятий и повышения квалификации.',
    TRUE,
    (SELECT id FROM workflow_templates WHERE default_template = TRUE),
    0,
    0
);

-- Команды со своим шаблоном по умолчанию сохраняют маршрут новых работ: их выбор становится командным шаблоном базового вида.
INSERT INTO interaction_kind_team_templates (kind_id, team_id, workflow_template_id)
SELECT '00000000-0000-0000-0000-000000000851', t.id, t.default_workflow_template_id
FROM teams t
WHERE t.default_workflow_template_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM workflow_templates w WHERE w.id = t.default_workflow_template_id);

ALTER TABLE interactions ADD COLUMN interaction_kind_id UUID REFERENCES interaction_kinds(id);

UPDATE interactions SET interaction_kind_id = '00000000-0000-0000-0000-000000000851';

ALTER TABLE interactions ALTER COLUMN interaction_kind_id SET NOT NULL;

CREATE INDEX interactions_kind_idx ON interactions (interaction_kind_id);
