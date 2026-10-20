-- Учёт людей и часов (пакет w62): преподавание сотрудниками в вузах, стажировки, повышение квалификации
-- преподавателей по людям. Каждая запись хранит единицу часа на момент ввода: смена настройки не переписывает
-- прежние записи, а отчёт приводит часы к единице из настройки.
CREATE TABLE people_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    teaching_unit VARCHAR(16) NOT NULL CHECK (teaching_unit IN ('ACADEMIC', 'ASTRONOMICAL')),
    internship_unit VARCHAR(16) NOT NULL CHECK (internship_unit IN ('ACADEMIC', 'ASTRONOMICAL')),
    qualification_unit VARCHAR(16) NOT NULL CHECK (qualification_unit IN ('ACADEMIC', 'ASTRONOMICAL')),
    frdo_deadline_days INTEGER CHECK (frdo_deadline_days BETWEEN 1 AND 365),
    frdo_notice_days INTEGER NOT NULL CHECK (frdo_notice_days BETWEEN 0 AND 90),
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE university_teaching (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    interaction_id UUID REFERENCES interactions(id),
    agreement_id UUID REFERENCES agreements(id) ON DELETE SET NULL,
    agreement_activity_id UUID REFERENCES agreement_activities(id) ON DELETE SET NULL,
    discipline VARCHAR(300) NOT NULL,
    performer_profile_id UUID REFERENCES crm_user_profiles(id),
    performer_name VARCHAR(300),
    performer_company VARCHAR(300),
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    hours NUMERIC(7, 1) NOT NULL CHECK (hours > 0),
    hour_unit VARCHAR(16) NOT NULL CHECK (hour_unit IN ('ACADEMIC', 'ASTRONOMICAL')),
    student_group VARCHAR(300),
    confirmation_status VARCHAR(16) NOT NULL CHECK (confirmation_status IN ('NOT_REQUESTED', 'REQUESTED', 'CONFIRMED', 'REJECTED')),
    confirmation_document VARCHAR(300),
    confirmed_on DATE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT university_teaching_period CHECK (starts_on <= ends_on),
    CONSTRAINT university_teaching_performer CHECK (performer_profile_id IS NOT NULL OR performer_name IS NOT NULL)
);

CREATE INDEX university_teaching_organization_idx ON university_teaching (organization_id, starts_on);

CREATE TABLE internships (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    interaction_id UUID REFERENCES interactions(id),
    agreement_activity_id UUID REFERENCES agreement_activities(id) ON DELETE SET NULL,
    intern_name VARCHAR(300) NOT NULL,
    intern_kind VARCHAR(16) NOT NULL CHECK (intern_kind IN ('STUDENT', 'TEACHER')),
    place VARCHAR(300) NOT NULL,
    mentor_profile_id UUID REFERENCES crm_user_profiles(id),
    mentor_name VARCHAR(300),
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    intern_hours NUMERIC(7, 1) NOT NULL CHECK (intern_hours > 0),
    mentor_hours NUMERIC(7, 1) CHECK (mentor_hours >= 0),
    hour_unit VARCHAR(16) NOT NULL CHECK (hour_unit IN ('ACADEMIC', 'ASTRONOMICAL')),
    result VARCHAR(16) NOT NULL CHECK (result IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'NOT_COMPLETED')),
    employed BOOLEAN NOT NULL DEFAULT FALSE,
    document VARCHAR(300),
    anonymized_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT internships_period CHECK (starts_on <= ends_on)
);

CREATE INDEX internships_organization_idx ON internships (organization_id, starts_on);

CREATE TABLE teacher_qualifications (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    interaction_id UUID REFERENCES interactions(id),
    agreement_activity_id UUID REFERENCES agreement_activities(id) ON DELETE SET NULL,
    teacher_roster_id UUID REFERENCES teacher_rosters(id) ON DELETE SET NULL,
    contact_id UUID REFERENCES contacts(id),
    person_name VARCHAR(300) NOT NULL,
    person_email VARCHAR(254),
    program VARCHAR(300) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    hours NUMERIC(7, 1) NOT NULL CHECK (hours > 0),
    hour_unit VARCHAR(16) NOT NULL CHECK (hour_unit IN ('ACADEMIC', 'ASTRONOMICAL')),
    result VARCHAR(16) NOT NULL CHECK (result IN ('IN_PROGRESS', 'CERTIFIED', 'NOT_CERTIFIED')),
    certificate_number VARCHAR(100),
    certificate_date DATE,
    frdo_submitted_on DATE,
    anonymized_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT teacher_qualifications_period CHECK (starts_on <= ends_on),
    CONSTRAINT teacher_qualifications_frdo CHECK (frdo_submitted_on IS NULL OR result = 'CERTIFIED')
);

CREATE INDEX teacher_qualifications_organization_idx ON teacher_qualifications (organization_id, ends_on);
