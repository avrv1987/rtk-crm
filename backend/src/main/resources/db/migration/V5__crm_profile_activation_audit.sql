ALTER TABLE crm_user_profiles
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE organization_assignment_events
    DROP CONSTRAINT organization_assignment_events_command_key;

ALTER TABLE organization_assignment_events
    ADD CONSTRAINT organization_assignment_events_command_organization_key UNIQUE (command_id, organization_id);

CREATE TABLE crm_profile_active_events (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    actor_display_name VARCHAR(200) NOT NULL,
    previous_active BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version >= 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT crm_profile_active_events_command_key UNIQUE (command_id),
    CONSTRAINT crm_profile_active_events_change_check CHECK (previous_active IS DISTINCT FROM active)
);

CREATE INDEX crm_user_profiles_admin_list_idx
    ON crm_user_profiles (display_name, id);

CREATE INDEX organizations_owner_manager_id_idx
    ON organizations (owner_manager_id, id);

CREATE INDEX crm_profile_active_events_profile_history_idx
    ON crm_profile_active_events (profile_id, occurred_at, id);
