ALTER TABLE crm_user_profiles ADD COLUMN pending_activation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crm_user_profiles ADD CONSTRAINT crm_user_profiles_pending_inactive_check
    CHECK (NOT (pending_activation AND active));

CREATE INDEX crm_user_profiles_pending_idx ON crm_user_profiles (display_name, id) WHERE pending_activation;
CREATE INDEX crm_user_profiles_team_role_idx ON crm_user_profiles (team_id, role);

ALTER TABLE teams ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE teams ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE UNIQUE INDEX teams_name_lower_key ON teams (LOWER(name));

CREATE TABLE crm_profile_events (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    actor_display_name VARCHAR(200) NOT NULL,
    previous_display_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    previous_role VARCHAR(16) NOT NULL CHECK (previous_role IN ('USER', 'LEADER', 'ADMIN')),
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'LEADER', 'ADMIN')),
    previous_team_id UUID REFERENCES teams(id),
    team_id UUID REFERENCES teams(id),
    previous_active BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version >= 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT crm_profile_events_command_key UNIQUE (command_id),
    CONSTRAINT crm_profile_events_change_check CHECK (
        previous_display_name <> display_name
        OR previous_role <> role
        OR previous_team_id IS DISTINCT FROM team_id
        OR previous_active <> active
    )
);

INSERT INTO crm_profile_events (
    id, profile_id, command_id, actor_profile_id, actor_display_name,
    previous_display_name, display_name, previous_role, role, previous_team_id, team_id,
    previous_active, active, request_id, version, occurred_at
)
SELECT event.id, event.profile_id, event.command_id, event.actor_profile_id, event.actor_display_name,
       profile.display_name, profile.display_name, profile.role, profile.role, profile.team_id, profile.team_id,
       event.previous_active, event.active, event.request_id, event.version, event.occurred_at
FROM crm_profile_active_events event
JOIN crm_user_profiles profile ON profile.id = event.profile_id;

DROP TABLE crm_profile_active_events;

CREATE INDEX crm_profile_events_profile_history_idx ON crm_profile_events (profile_id, occurred_at, id);

CREATE TABLE organization_team_events (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
    previous_team_id UUID NOT NULL REFERENCES teams(id),
    team_id UUID NOT NULL REFERENCES teams(id),
    previous_owner_manager_id UUID REFERENCES crm_user_profiles(id),
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    actor_display_name VARCHAR(200) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version >= 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT organization_team_events_command_key UNIQUE (command_id),
    CONSTRAINT organization_team_events_change_check CHECK (previous_team_id <> team_id)
);

CREATE INDEX organization_team_events_history_idx ON organization_team_events (organization_id, occurred_at, id);
