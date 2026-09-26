CREATE TABLE report_jobs (
    id UUID PRIMARY KEY,
    owner_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    owner_role VARCHAR(16) NOT NULL CHECK (owner_role IN ('USER', 'LEADER', 'ADMIN')),
    owner_team_id UUID,
    owner_access_revision INTEGER NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PORTFOLIO', 'EVENTS')),
    format VARCHAR(8) NOT NULL CHECK (format IN ('XLSX', 'XLS', 'PDF', 'JSON', 'PNG')),
    group_by VARCHAR(16) CHECK (group_by IN ('STAGE', 'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'PRODUCT', 'MANAGER', 'MONTH')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    row_count INTEGER CHECK (row_count >= 0),
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    result_storage_key UUID UNIQUE,
    result_file_name VARCHAR(255),
    result_size_bytes BIGINT CHECK (result_size_bytes >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT report_jobs_owner_idempotency_key UNIQUE (owner_profile_id, idempotency_key),
    CONSTRAINT report_jobs_chart_format CHECK ((group_by IS NULL AND format <> 'PNG') OR (group_by IS NOT NULL AND format IN ('PNG', 'PDF')))
);

CREATE INDEX report_jobs_owner_created_idx ON report_jobs (owner_profile_id, created_at DESC, id);
CREATE INDEX report_jobs_unfinished_idx ON report_jobs (status) WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX interaction_events_occurred_idx ON interaction_events (occurred_at, id);
CREATE INDEX interaction_events_owner_snapshot_occurred_idx ON interaction_events (owner_manager_id_snapshot, occurred_at);
CREATE INDEX interactions_created_idx ON interactions (created_at, id);
CREATE INDEX product_agreements_product_interaction_idx ON product_agreements (product_id, interaction_id);
