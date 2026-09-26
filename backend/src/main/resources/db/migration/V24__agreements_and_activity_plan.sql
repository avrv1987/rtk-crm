CREATE TABLE agreement_activity_kinds (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    sort_order INTEGER NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX agreement_activity_kinds_name_lower_key ON agreement_activity_kinds (LOWER(name));

INSERT INTO agreement_activity_kinds (id, name, sort_order) VALUES
    ('7c2f0a10-0000-4000-8000-000000000010', 'Разработка и актуализация образовательных программ', 10),
    ('7c2f0a10-0000-4000-8000-000000000020', 'Участие преподавателей-практиков', 20),
    ('7c2f0a10-0000-4000-8000-000000000030', 'Стажировки и практики студентов', 30),
    ('7c2f0a10-0000-4000-8000-000000000040', 'Обучение и повышение квалификации преподавателей', 40),
    ('7c2f0a10-0000-4000-8000-000000000050', 'Предоставление доступа к ИТ-продуктам, стендам и лабораториям', 50),
    ('7c2f0a10-0000-4000-8000-000000000060', 'Совместные мероприятия и конкурсы', 60),
    ('7c2f0a10-0000-4000-8000-000000000070', 'Иное', 70);

CREATE TABLE agreements (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    number VARCHAR(100) NOT NULL,
    concluded_on DATE,
    valid_until DATE,
    parties VARCHAR(2000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'TERMINATED')),
    file_attachment_id UUID REFERENCES attachments(id),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agreements_organization_number_key UNIQUE (organization_id, number),
    CONSTRAINT agreements_term CHECK (concluded_on IS NULL OR valid_until IS NULL OR concluded_on <= valid_until)
);

CREATE TABLE agreement_activities (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    kind_id UUID NOT NULL REFERENCES agreement_activity_kinds(id),
    title VARCHAR(300) NOT NULL,
    unit VARCHAR(50),
    planned_volume INTEGER CHECK (planned_volume >= 0),
    actual_volume INTEGER CHECK (actual_volume >= 0),
    planned_start DATE,
    planned_end DATE,
    actual_start DATE,
    actual_end DATE,
    responsible_profile_id UUID REFERENCES crm_user_profiles(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agreement_activities_planned CHECK (planned_start IS NULL OR planned_end IS NULL OR planned_start <= planned_end),
    CONSTRAINT agreement_activities_actual CHECK (actual_start IS NULL OR actual_end IS NULL OR actual_start <= actual_end)
);

CREATE TABLE agreement_activity_interactions (
    activity_id UUID NOT NULL REFERENCES agreement_activities(id) ON DELETE CASCADE,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    PRIMARY KEY (activity_id, interaction_id)
);

CREATE TABLE agreement_activity_attachments (
    activity_id UUID NOT NULL REFERENCES agreement_activities(id) ON DELETE CASCADE,
    attachment_id UUID NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    PRIMARY KEY (activity_id, attachment_id)
);

CREATE INDEX agreements_organization_idx ON agreements (organization_id, concluded_on, id);
CREATE INDEX agreement_activities_agreement_idx ON agreement_activities (agreement_id, id);
CREATE INDEX agreement_activities_kind_idx ON agreement_activities (kind_id);
CREATE INDEX agreement_activity_interactions_interaction_idx ON agreement_activity_interactions (interaction_id);
CREATE INDEX agreement_activity_attachments_attachment_idx ON agreement_activity_attachments (attachment_id);

ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_kind_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_kind_check
    CHECK (kind IN ('PORTFOLIO', 'EVENTS', 'DEMAND', 'SNAPSHOT', 'DURATION', 'AGREEMENTS'));
