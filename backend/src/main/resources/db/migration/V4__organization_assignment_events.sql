CREATE TABLE organization_assignment_events (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
    previous_owner_manager_id UUID REFERENCES crm_user_profiles(id),
    previous_owner_manager_display_name VARCHAR(200),
    owner_manager_id UUID REFERENCES crm_user_profiles(id),
    new_owner_manager_display_name VARCHAR(200),
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    actor_display_name VARCHAR(200) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version >= 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT organization_assignment_events_command_key UNIQUE (command_id),
    CONSTRAINT organization_assignment_events_owner_change_check
        CHECK (previous_owner_manager_id IS DISTINCT FROM owner_manager_id)
);

CREATE INDEX organization_assignment_events_history_idx
    ON organization_assignment_events (organization_id, occurred_at, id);
