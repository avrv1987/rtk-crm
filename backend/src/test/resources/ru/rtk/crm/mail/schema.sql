CREATE TABLE IF NOT EXISTS teams (
    id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, version INTEGER DEFAULT 0 NOT NULL,
    archived BOOLEAN DEFAULT FALSE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS crm_user_profiles (
    id UUID PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL,
    display_name VARCHAR(200) NOT NULL, login VARCHAR(200), role VARCHAR(16) NOT NULL, team_id UUID,
    active BOOLEAN NOT NULL DEFAULT TRUE, pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
    enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, idp_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    activation_requested_at TIMESTAMP WITH TIME ZONE, anonymized_at TIMESTAMP WITH TIME ZONE,
    access_revision INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0,
    partner_organization_id UUID, partner_contact_id UUID UNIQUE,
    role_synced_at TIMESTAMP WITH TIME ZONE, last_login_at TIMESTAMP WITH TIME ZONE,
    role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (issuer, subject)
);

CREATE TABLE IF NOT EXISTS command_idempotency_records (
    id UUID PRIMARY KEY, actor_profile_id UUID NOT NULL, operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL, request_fingerprint CHAR(64) NOT NULL, result_json VARCHAR(10000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE (actor_profile_id, operation, idempotency_key)
);

CREATE TABLE IF NOT EXISTS crm_profile_events (
    id UUID PRIMARY KEY, profile_id UUID NOT NULL, command_id UUID NOT NULL UNIQUE, actor_profile_id UUID NOT NULL,
    actor_display_name VARCHAR(200) NOT NULL, previous_display_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(200) NOT NULL, previous_role VARCHAR(16) NOT NULL, role VARCHAR(16) NOT NULL,
    previous_team_id UUID, team_id UUID, previous_active BOOLEAN NOT NULL, active BOOLEAN NOT NULL,
    previous_enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL, enrolment_operator BOOLEAN DEFAULT FALSE NOT NULL,
    request_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY, category VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL, actor_profile_id UUID,
    actor_display_name VARCHAR(200) NOT NULL, object_type VARCHAR(32), object_id UUID, object_name VARCHAR(500),
    details VARCHAR(2000), request_id VARCHAR(64), occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS organizations (
    id UUID PRIMARY KEY, name VARCHAR(300) NOT NULL, team_id UUID, owner_manager_id UUID
);

CREATE TABLE IF NOT EXISTS organization_assignment_events (
    id UUID PRIMARY KEY, organization_id UUID NOT NULL, command_id UUID, previous_owner_manager_id UUID,
    previous_owner_manager_display_name VARCHAR(200), owner_manager_id UUID, actor_profile_id UUID NOT NULL,
    reason VARCHAR(32), occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS agreements (
    id UUID PRIMARY KEY, organization_id UUID NOT NULL, number VARCHAR(100) NOT NULL, valid_until DATE, status VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS agreement_status_events (
    id UUID PRIMARY KEY, agreement_id UUID NOT NULL, from_status VARCHAR(16), to_status VARCHAR(16) NOT NULL,
    actor_profile_id UUID, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS source_records (
    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, record_type VARCHAR(64) NOT NULL, organization_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS interactions (interaction_kind_id UUID, 
    id UUID PRIMARY KEY, organization_id UUID NOT NULL
);

CREATE TABLE IF NOT EXISTS interaction_issues (
    id UUID PRIMARY KEY, interaction_id UUID NOT NULL, kind VARCHAR(16) NOT NULL, description VARCHAR(1000) NOT NULL,
    risk_level VARCHAR(16), responsible_profile_id UUID NOT NULL, due_on DATE, status VARCHAR(16) NOT NULL,
    resolution VARCHAR(1000), created_by UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_by UUID, resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS sync_runs (
    id UUID PRIMARY KEY, source VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, error_code VARCHAR(64),
    error_message VARCHAR(500), finished_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS crm_exchange_events (
    id UUID PRIMARY KEY, status VARCHAR(8) NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_change_plans (
    id UUID PRIMARY KEY, requester_profile_id UUID NOT NULL, interaction_id UUID NOT NULL, state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
