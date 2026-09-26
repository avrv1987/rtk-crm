CREATE TABLE sources (
    code VARCHAR(16) PRIMARY KEY CHECK (code IN ('WEBSITE', 'MOODLE')),
    updated_since TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sources (code) VALUES ('WEBSITE'), ('MOODLE');

CREATE TABLE sync_runs (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL REFERENCES sources(code),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    started_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_since TIMESTAMP WITH TIME ZONE,
    fetched_count INTEGER NOT NULL DEFAULT 0 CHECK (fetched_count >= 0),
    created_count INTEGER NOT NULL DEFAULT 0 CHECK (created_count >= 0),
    updated_count INTEGER NOT NULL DEFAULT 0 CHECK (updated_count >= 0),
    skipped_count INTEGER NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
    needs_mapping_count INTEGER NOT NULL DEFAULT 0 CHECK (needs_mapping_count >= 0),
    failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX sync_runs_active_source_idx ON sync_runs (source) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX sync_runs_source_created_idx ON sync_runs (source, created_at DESC, id);

CREATE TABLE source_records (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL REFERENCES sources(code),
    record_type VARCHAR(64) NOT NULL,
    external_id VARCHAR(200) NOT NULL,
    external_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    external_status VARCHAR(64),
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('APPLIED', 'NEEDS_MAPPING', 'FAILED', 'SKIPPED')),
    error VARCHAR(500),
    organization_id UUID REFERENCES organizations(id),
    program_id UUID REFERENCES programs(id),
    applications_count INTEGER NOT NULL DEFAULT 1 CHECK (applications_count > 0),
    interaction_id UUID REFERENCES interactions(id),
    sync_run_id UUID REFERENCES sync_runs(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT source_records_external_key UNIQUE (source, record_type, external_id)
);

CREATE INDEX source_records_status_idx ON source_records (status, updated_at DESC, id);
CREATE INDEX source_records_demand_idx ON source_records (record_type, status, submitted_at);
CREATE INDEX source_records_organization_idx ON source_records (organization_id);

CREATE TABLE source_mappings (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL REFERENCES sources(code),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ORGANIZATION', 'PROGRAM', 'COURSE', 'GROUP')),
    external_key VARCHAR(310) NOT NULL,
    organization_id UUID REFERENCES organizations(id),
    program_id UUID REFERENCES programs(id),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT source_mappings_key UNIQUE (source, kind, external_key),
    CONSTRAINT source_mappings_target CHECK (
        (kind = 'ORGANIZATION' AND organization_id IS NOT NULL AND program_id IS NULL)
        OR (kind = 'PROGRAM' AND program_id IS NOT NULL AND organization_id IS NULL)
        OR (kind IN ('COURSE', 'GROUP') AND organization_id IS NOT NULL AND program_id IS NOT NULL)
    )
);

CREATE TABLE learning_snapshots (
    source_record_id UUID PRIMARY KEY REFERENCES source_records(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    program_id UUID NOT NULL REFERENCES programs(id),
    course_id BIGINT NOT NULL,
    group_id BIGINT,
    course_name VARCHAR(1333) NOT NULL,
    group_name VARCHAR(300),
    participants_count INTEGER NOT NULL CHECK (participants_count >= 0),
    teachers_count INTEGER NOT NULL CHECK (teachers_count >= 0),
    completed_count INTEGER CHECK (completed_count >= 0),
    not_completed_count INTEGER CHECK (not_completed_count >= 0),
    unknown_count INTEGER NOT NULL CHECK (unknown_count >= 0),
    groups_count INTEGER NOT NULL CHECK (groups_count >= 0),
    parallel_runs INTEGER NOT NULL CHECK (parallel_runs >= 0),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sync_run_id UUID REFERENCES sync_runs(id),
    CONSTRAINT learning_snapshots_completion CHECK ((completed_count IS NULL) = (not_completed_count IS NULL))
);

CREATE INDEX learning_snapshots_program_idx ON learning_snapshots (program_id, organization_id);

ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_kind_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_kind_check CHECK (kind IN ('PORTFOLIO', 'EVENTS', 'DEMAND'));
