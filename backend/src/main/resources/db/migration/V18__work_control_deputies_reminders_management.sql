ALTER TABLE crm_user_profiles DROP CONSTRAINT crm_user_profiles_role_check;
ALTER TABLE crm_user_profiles ADD CONSTRAINT crm_user_profiles_role_check
    CHECK (role IN ('USER', 'LEADER', 'ADMIN', 'MANAGEMENT'));

ALTER TABLE crm_profile_events DROP CONSTRAINT crm_profile_events_previous_role_check;
ALTER TABLE crm_profile_events ADD CONSTRAINT crm_profile_events_previous_role_check
    CHECK (previous_role IN ('USER', 'LEADER', 'ADMIN', 'MANAGEMENT'));
ALTER TABLE crm_profile_events DROP CONSTRAINT crm_profile_events_role_check;
ALTER TABLE crm_profile_events ADD CONSTRAINT crm_profile_events_role_check
    CHECK (role IN ('USER', 'LEADER', 'ADMIN', 'MANAGEMENT'));

ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_owner_role_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_owner_role_check
    CHECK (owner_role IN ('USER', 'LEADER', 'ADMIN', 'MANAGEMENT'));

ALTER TABLE interactions ADD COLUMN IF NOT EXISTS work_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (work_status IN ('ACTIVE', 'PAUSED', 'COMPLETED'));

CREATE INDEX interaction_events_stage_entry_idx ON interaction_events (interaction_id, to_stage_id, occurred_at);

CREATE TABLE organization_deputies (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    deputy_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    deputy_display_name VARCHAR(200) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    actor_display_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    ended_by_profile_id UUID REFERENCES crm_user_profiles(id),
    ended_by_display_name VARCHAR(200),
    CONSTRAINT organization_deputies_period_check CHECK (starts_on <= ends_on AND starts_at < ends_at),
    CONSTRAINT organization_deputies_ended_by_check CHECK (ended_by_profile_id IS NULL OR ended_at IS NOT NULL)
);

CREATE UNIQUE INDEX organization_deputies_open_key ON organization_deputies (organization_id) WHERE ended_at IS NULL;
CREATE INDEX organization_deputies_deputy_open_idx ON organization_deputies (deputy_profile_id, organization_id) WHERE ended_at IS NULL;
CREATE INDEX organization_deputies_history_idx ON organization_deputies (organization_id, created_at, id);

CREATE TABLE reminder_settings (
    profile_id UUID PRIMARY KEY REFERENCES crm_user_profiles(id),
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
