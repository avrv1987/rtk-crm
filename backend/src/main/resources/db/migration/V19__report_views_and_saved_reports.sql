ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_kind_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_kind_check
    CHECK (kind IN ('PORTFOLIO', 'EVENTS', 'DEMAND', 'SNAPSHOT', 'DURATION'));
ALTER TABLE report_jobs ADD COLUMN chart_type VARCHAR(8) CHECK (chart_type IN ('BAR', 'LINE'));
ALTER TABLE report_jobs ADD COLUMN series_by VARCHAR(16)
    CHECK (series_by IN ('STAGE', 'ORGANIZATION', 'DIRECTION', 'PROGRAM', 'PRODUCT', 'MANAGER'));

CREATE TABLE saved_reports (
    id UUID PRIMARY KEY,
    owner_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    name VARCHAR(200) NOT NULL,
    definition_json TEXT NOT NULL,
    period_preset VARCHAR(32) CHECK (period_preset IN ('CURRENT_MONTH', 'PREVIOUS_MONTH', 'CURRENT_QUARTER', 'PREVIOUS_QUARTER')),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX saved_reports_owner_name_key ON saved_reports (owner_profile_id, lower(name));

CREATE INDEX organization_assignment_events_occurred_idx ON organization_assignment_events (occurred_at, id);
