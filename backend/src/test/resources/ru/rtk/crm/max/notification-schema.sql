CREATE TABLE IF NOT EXISTS organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    team_id UUID,
    owner_manager_id UUID
);

CREATE TABLE IF NOT EXISTS organization_assignment_events (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    owner_manager_id UUID,
    actor_profile_id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS agreements (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    number VARCHAR(100) NOT NULL,
    valid_until DATE,
    status VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS agreement_status_events (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    actor_profile_id UUID,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS source_records (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL,
    record_type VARCHAR(64) NOT NULL,
    organization_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS interactions (interaction_kind_id UUID, 
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_change_plans (
    id UUID PRIMARY KEY,
    requester_profile_id UUID NOT NULL,
    interaction_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
