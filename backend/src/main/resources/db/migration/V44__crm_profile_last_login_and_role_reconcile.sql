ALTER TABLE crm_user_profiles ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm_user_profiles ADD COLUMN role_reconcile_required BOOLEAN NOT NULL DEFAULT FALSE;
