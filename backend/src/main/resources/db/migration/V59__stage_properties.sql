-- Справочник «Виды документов» заменяет прежний закрытый список вида документа. Код хранится в attachments.kind и
-- в обязательных документах этапа: у прежних видов код — прежнее значение списка, у новых — шестнадцатеричный
-- идентификатор записи. Ведёт справочник администратор через общий механизм справочников.
CREATE TABLE document_kinds (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL UNIQUE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO document_kinds (id, code, name) VALUES
    ('00000000-0000-0000-0000-000000000901', 'CONTRACT', 'Договор'),
    ('00000000-0000-0000-0000-000000000902', 'LICENSE_AGREEMENT', 'Лицензионное соглашение'),
    ('00000000-0000-0000-0000-000000000903', 'APPENDIX', 'Приложение'),
    ('00000000-0000-0000-0000-000000000904', 'ACT', 'Акт'),
    ('00000000-0000-0000-0000-000000000905', 'SIGNED_SCAN', 'Скан подписанного документа'),
    ('00000000-0000-0000-0000-000000000906', 'MATERIALS', 'Учебные материалы'),
    ('00000000-0000-0000-0000-000000000907', 'DOCUMENTATION', 'Документация продукта'),
    ('00000000-0000-0000-0000-000000000908', 'CURRICULUM', 'Рабочая программа'),
    ('00000000-0000-0000-0000-000000000909', 'QUALIFICATION', 'Документ о повышении квалификации'),
    ('00000000-0000-0000-0000-000000000910', 'OTHER', 'Иное');

ALTER TABLE attachments DROP CONSTRAINT attachments_kind_check;
ALTER TABLE attachments ADD CONSTRAINT attachments_kind_fk FOREIGN KEY (kind) REFERENCES document_kinds(code);

ALTER TABLE catalog_change_events DROP CONSTRAINT catalog_change_events_entity_type_check;
ALTER TABLE catalog_change_events ADD CONSTRAINT catalog_change_events_entity_type_check CHECK (entity_type IN (
    'ORGANIZATION', 'TEAM', 'DIRECTION', 'PROGRAM', 'VENDOR', 'PRODUCT', 'AGREEMENT', 'VENDOR_CONTACT', 'DOCUMENT_KIND'
));
ALTER TABLE catalog_name_locks DROP CONSTRAINT catalog_name_locks_entity_type_check;
ALTER TABLE catalog_name_locks ADD CONSTRAINT catalog_name_locks_entity_type_check CHECK (entity_type IN (
    'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'VENDOR', 'PRODUCT', 'DOCUMENT_KIND'
));
INSERT INTO catalog_name_locks (entity_type) VALUES ('DOCUMENT_KIND');

-- Свойства этапа: «требует действующего договора» и роль для LMS (NULL — без связи с LMS). Те же столбцы у этапа
-- шаблона и у этапа карточки: карточка получает копию при создании и при переводе на версию шаблона.
ALTER TABLE workflow_template_stages ADD COLUMN requires_agreement BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow_template_stages ADD COLUMN lms_role VARCHAR(32);
ALTER TABLE workflow_template_stages ADD CONSTRAINT workflow_template_stages_lms_role_check
    CHECK (lms_role IN ('TEACHER_TRAINING', 'CLASSES', 'QUALIFICATION'));
ALTER TABLE interaction_stages ADD COLUMN requires_agreement BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE interaction_stages ADD COLUMN lms_role VARCHAR(32);
ALTER TABLE interaction_stages ADD CONSTRAINT interaction_stages_lms_role_check
    CHECK (lms_role IN ('TEACHER_TRAINING', 'CLASSES', 'QUALIFICATION'));

CREATE TABLE workflow_template_stage_documents (
    stage_id UUID NOT NULL REFERENCES workflow_template_stages(id) ON DELETE CASCADE,
    kind_code VARCHAR(32) NOT NULL REFERENCES document_kinds(code),
    PRIMARY KEY (stage_id, kind_code)
);

CREATE TABLE interaction_stage_documents (
    interaction_id UUID NOT NULL,
    stage_id UUID NOT NULL,
    kind_code VARCHAR(32) NOT NULL REFERENCES document_kinds(code),
    PRIMARY KEY (stage_id, kind_code),
    CONSTRAINT interaction_stage_documents_stage_fk
        FOREIGN KEY (stage_id, interaction_id) REFERENCES interaction_stages(id, interaction_id) ON DELETE CASCADE
);

CREATE INDEX interaction_stage_documents_interaction_idx ON interaction_stage_documents (interaction_id);

-- Отметки по прежней логике, чтобы поведение не изменилось. Правило подписания находило этап по идентификатору
-- этапа базового шаблона (…0816) или по названию «Подписание» без учёта регистра и краевых пробелов
-- (app.agreements.signing-stage-names). Сигналы и подсказки LMS сравнивали название точно
-- (app.lms-signals.classes-stage-name, app.work.training-stage-names, app.work.qualification-stage-name).
UPDATE workflow_template_stages SET requires_agreement = TRUE
WHERE id = '00000000-0000-0000-0000-000000000816' OR LOWER(TRIM(name)) = 'подписание';
UPDATE interaction_stages SET requires_agreement = TRUE
WHERE template_stage_id = '00000000-0000-0000-0000-000000000816' OR LOWER(TRIM(name)) = 'подписание';

UPDATE workflow_template_stages SET lms_role = CASE name
    WHEN 'Обучение преподавателей' THEN 'TEACHER_TRAINING'
    WHEN 'Занятия' THEN 'CLASSES'
    WHEN 'Повышение квалификации' THEN 'QUALIFICATION'
END
WHERE name IN ('Обучение преподавателей', 'Занятия', 'Повышение квалификации');
UPDATE interaction_stages SET lms_role = CASE name
    WHEN 'Обучение преподавателей' THEN 'TEACHER_TRAINING'
    WHEN 'Занятия' THEN 'CLASSES'
    WHEN 'Повышение квалификации' THEN 'QUALIFICATION'
END
WHERE name IN ('Обучение преподавателей', 'Занятия', 'Повышение квалификации');

-- Группировка статистики «по вехам» (MILESTONE). В тот же список добавлена группировка «по видам взаимодействия»
-- (KIND, версия 2.5): без неё асинхронная выгрузка диаграммы по видам не записывалась в report_jobs.
ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_group_by_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_group_by_check CHECK (group_by IN (
    'STAGE', 'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'PRODUCT', 'MANAGER', 'MONTH', 'KIND', 'MILESTONE'
));
ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_series_by_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_series_by_check CHECK (series_by IN (
    'STAGE', 'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'PRODUCT', 'MANAGER', 'KIND'
));
