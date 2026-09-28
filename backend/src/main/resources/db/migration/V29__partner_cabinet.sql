ALTER TABLE crm_user_profiles DROP CONSTRAINT crm_user_profiles_role_check;
ALTER TABLE crm_user_profiles ADD CONSTRAINT crm_user_profiles_role_check
    CHECK (role IN ('USER', 'LEADER', 'ADMIN', 'MANAGEMENT', 'PARTNER'));

ALTER TABLE crm_profile_events DROP CONSTRAINT crm_profile_events_previous_role_check;
ALTER TABLE crm_profile_events ADD CONSTRAINT crm_profile_events_previous_role_check
    CHECK (previous_role IN ('USER', 'LEADER', 'ADMIN', 'MANAGEMENT', 'PARTNER'));
ALTER TABLE crm_profile_events DROP CONSTRAINT crm_profile_events_role_check;
ALTER TABLE crm_profile_events ADD CONSTRAINT crm_profile_events_role_check
    CHECK (role IN ('USER', 'LEADER', 'ADMIN', 'MANAGEMENT', 'PARTNER'));

ALTER TABLE crm_user_profiles ADD COLUMN partner_organization_id UUID;
ALTER TABLE crm_user_profiles ADD COLUMN partner_contact_id UUID;
ALTER TABLE crm_user_profiles ADD CONSTRAINT crm_user_profiles_partner_contact_fk
    FOREIGN KEY (partner_contact_id, partner_organization_id) REFERENCES contacts(id, organization_id);
ALTER TABLE crm_user_profiles ADD CONSTRAINT crm_user_profiles_partner_binding_check CHECK (
    (role = 'PARTNER' AND partner_organization_id IS NOT NULL AND partner_contact_id IS NOT NULL AND team_id IS NULL)
    OR (role <> 'PARTNER' AND partner_organization_id IS NULL AND partner_contact_id IS NULL)
);
CREATE UNIQUE INDEX crm_user_profiles_partner_contact_key ON crm_user_profiles (partner_contact_id)
    WHERE partner_contact_id IS NOT NULL;
CREATE INDEX crm_user_profiles_partner_organization_idx ON crm_user_profiles (partner_organization_id)
    WHERE partner_organization_id IS NOT NULL;

ALTER TABLE interactions ADD COLUMN next_step_partner_visible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE attachments ADD COLUMN partner_visible BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX attachments_partner_visible_idx ON attachments (interaction_id) WHERE partner_visible;
