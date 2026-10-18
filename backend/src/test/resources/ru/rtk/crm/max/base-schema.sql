CREATE TABLE IF NOT EXISTS crm_user_profiles (
    id UUID PRIMARY KEY,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(512) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    login VARCHAR(200),
    role VARCHAR(16) NOT NULL,
    team_id UUID,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    pending_activation BOOLEAN NOT NULL DEFAULT FALSE,
    access_revision INTEGER NOT NULL DEFAULT 0,
    enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE,
    role_synced_at TIMESTAMP WITH TIME ZONE,
    last_login_at TIMESTAMP WITH TIME ZONE,
    role_reconcile_required BOOLEAN DEFAULT FALSE NOT NULL,
    partner_organization_id UUID,
    partner_contact_id UUID,
    UNIQUE (issuer, subject)
);

CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor_profile_id UUID,
    actor_display_name VARCHAR(200) NOT NULL,
    object_type VARCHAR(32),
    object_id UUID,
    object_name VARCHAR(500),
    details VARCHAR(2000),
    request_id VARCHAR(64),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
