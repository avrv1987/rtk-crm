CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor_profile_id UUID REFERENCES crm_user_profiles(id),
    actor_display_name VARCHAR(200) NOT NULL,
    object_type VARCHAR(32),
    object_id UUID,
    object_name VARCHAR(500),
    details VARCHAR(2000),
    request_id VARCHAR(64),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX audit_events_occurred_idx ON audit_events (occurred_at DESC, id DESC);
CREATE INDEX audit_events_actor_idx ON audit_events (actor_profile_id, occurred_at DESC);

ALTER TABLE contacts ADD COLUMN personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE contacts ADD CONSTRAINT contacts_personal_data_status_check
    CHECK (personal_data_status IN ('ACTIVE', 'RESTRICTED', 'ANONYMIZED'));

ALTER TABLE crm_user_profiles ADD COLUMN login VARCHAR(200);
ALTER TABLE crm_user_profiles ADD COLUMN idp_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE crm_user_profiles ADD COLUMN activation_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm_user_profiles ADD COLUMN anonymized_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX crm_user_profiles_login_idx ON crm_user_profiles (LOWER(login));
