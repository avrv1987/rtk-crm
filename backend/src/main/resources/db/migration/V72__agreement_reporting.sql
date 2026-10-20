-- Настраиваемая отчётность по соглашениям с вузами. Значения нормативов (ставки, сроки, порог, срок «молчания»)
-- не зашиваются: справочники и настройки по умолчанию пусты, их ведёт администратор.

CREATE TABLE agreement_reference_items (
    id UUID PRIMARY KEY,
    category VARCHAR(16) NOT NULL CHECK (category IN ('EXECUTOR', 'EDUCATION_LEVEL', 'UNIT')),
    name VARCHAR(200) NOT NULL,
    base_amount NUMERIC(16, 2) CHECK (base_amount >= 0),
    sort_order INTEGER NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX agreement_reference_items_name_key ON agreement_reference_items (category, LOWER(name));

ALTER TABLE agreement_activity_kinds ADD COLUMN required_attributes VARCHAR(200);

ALTER TABLE agreement_activities ADD COLUMN education_level_id UUID REFERENCES agreement_reference_items(id);
ALTER TABLE agreement_activities ADD COLUMN participants INTEGER CHECK (participants >= 0);
ALTER TABLE agreement_activities ADD COLUMN unit_id UUID REFERENCES agreement_reference_items(id);
ALTER TABLE agreement_activities ADD COLUMN cost NUMERIC(16, 2) CHECK (cost >= 0);
ALTER TABLE agreement_activities ADD COLUMN executor_id UUID REFERENCES agreement_reference_items(id);
ALTER TABLE agreement_activities ADD COLUMN document_note VARCHAR(500);

ALTER TABLE agreement_activities DROP CONSTRAINT agreement_activities_status_check;
ALTER TABLE agreement_activities ADD CONSTRAINT agreement_activities_status_check
    CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'DONE', 'CONFIRMED', 'REJECTED', 'CANCELLED'));

CREATE INDEX agreement_activities_executor_idx ON agreement_activities (executor_id);

CREATE TABLE agreement_rates (
    id UUID PRIMARY KEY,
    kind_id UUID NOT NULL REFERENCES agreement_activity_kinds(id),
    unit_id UUID NOT NULL REFERENCES agreement_reference_items(id),
    education_level_id UUID REFERENCES agreement_reference_items(id),
    effective_from DATE NOT NULL,
    rate NUMERIC(16, 2) NOT NULL CHECK (rate >= 0),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX agreement_rates_lookup_idx ON agreement_rates (kind_id, unit_id, effective_from);

-- Одна строка настроек; значения по умолчанию ничего не включают: «молчание», порог и сроки выключены.
CREATE TABLE agreement_reporting_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    period_kind VARCHAR(16) NOT NULL CHECK (period_kind IN ('CALENDAR', 'ACADEMIC')),
    academic_year_start_month INTEGER NOT NULL CHECK (academic_year_start_month BETWEEN 1 AND 12),
    silence_days INTEGER CHECK (silence_days BETWEEN 1 AND 365),
    repeat_silence_days INTEGER CHECK (repeat_silence_days BETWEEN 1 AND 365),
    reminder_days INTEGER NOT NULL CHECK (reminder_days BETWEEN 1 AND 365),
    threshold_enabled BOOLEAN NOT NULL,
    threshold_percent NUMERIC(7, 3) CHECK (threshold_percent > 0 AND threshold_percent <= 100),
    threshold_base NUMERIC(16, 2) CHECK (threshold_base >= 0),
    threshold_base_label VARCHAR(300),
    report_columns TEXT,
    updated_by UUID REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE
);

INSERT INTO agreement_reporting_settings (id, period_kind, academic_year_start_month, reminder_days, threshold_enabled)
VALUES (1, 'CALENDAR', 9, 14, FALSE);

CREATE TABLE agreement_reporting_deadlines (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    due_on DATE NOT NULL,
    deliverable VARCHAR(500),
    annual BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL
);

CREATE TABLE agreement_activity_lists (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    title VARCHAR(200) NOT NULL,
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    period_label VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'APPROVED_SILENTLY', 'RETURNED')),
    review_round INTEGER NOT NULL DEFAULT 0 CHECK (review_round >= 0),
    sent_on DATE,
    review_due_on DATE,
    decided_on DATE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT agreement_activity_lists_period CHECK (period_from <= period_to)
);

CREATE INDEX agreement_activity_lists_organization_idx ON agreement_activity_lists (organization_id, period_from);
CREATE INDEX agreement_activity_lists_review_idx ON agreement_activity_lists (status, review_due_on);

-- Позиция перечня хранит снимок мероприятия на момент формирования: согласованный вузом перечень не меняется,
-- если план договора потом правят.
CREATE TABLE agreement_activity_list_items (
    list_id UUID NOT NULL REFERENCES agreement_activity_lists(id) ON DELETE CASCADE,
    activity_id UUID REFERENCES agreement_activities(id) ON DELETE SET NULL,
    item_order INTEGER NOT NULL,
    agreement_number VARCHAR(100) NOT NULL,
    kind_name VARCHAR(200) NOT NULL,
    title VARCHAR(300) NOT NULL,
    education_level VARCHAR(200),
    executor VARCHAR(200),
    unit VARCHAR(50),
    planned_volume INTEGER,
    participants INTEGER,
    cost NUMERIC(16, 2),
    period_start DATE,
    period_end DATE,
    PRIMARY KEY (list_id, item_order)
);

CREATE TABLE agreement_activity_list_events (
    id UUID PRIMARY KEY,
    list_id UUID NOT NULL REFERENCES agreement_activity_lists(id) ON DELETE CASCADE,
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('KAM', 'COORDINATOR', 'SYSTEM')),
    comment VARCHAR(2000),
    actor_profile_id UUID REFERENCES crm_user_profiles(id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX agreement_activity_list_events_list_idx ON agreement_activity_list_events (list_id, occurred_at);
