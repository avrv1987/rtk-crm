ALTER TABLE source_records ADD COLUMN stream_no INTEGER CHECK (stream_no > 0);
ALTER TABLE source_records ADD COLUMN payload_hash CHAR(64);
CREATE INDEX source_records_paid_order_idx ON source_records (program_id, stream_no) WHERE record_type = 'paid_order';

ALTER TABLE sync_runs DROP CONSTRAINT sync_runs_run_trigger_check;
ALTER TABLE sync_runs ADD CONSTRAINT sync_runs_run_trigger_check
    CHECK (run_trigger IN ('MANUAL', 'SCHEDULE', 'CARD', 'BOOTSTRAP', 'UPLOAD'));

ALTER TABLE crm_user_profiles ADD COLUMN enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crm_user_profiles ADD CONSTRAINT crm_user_profiles_enrolment_operator_role
    CHECK (NOT enrolment_operator OR role IN ('USER', 'LEADER'));

ALTER TABLE crm_profile_events ADD COLUMN previous_enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crm_profile_events ADD COLUMN enrolment_operator BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crm_profile_events DROP CONSTRAINT crm_profile_events_change_check;
ALTER TABLE crm_profile_events ADD CONSTRAINT crm_profile_events_change_check CHECK (
    previous_display_name <> display_name
    OR previous_role <> role
    OR previous_team_id IS DISTINCT FROM team_id
    OR previous_active <> active
    OR previous_enrolment_operator <> enrolment_operator
);

ALTER TABLE organizations DROP CONSTRAINT organizations_type_check;
ALTER TABLE organizations ADD CONSTRAINT organizations_type_check
    CHECK (type IN ('UNIVERSITY', 'SCHOOL', 'COLLEGE', 'OPEN_ENROLLMENT'));

INSERT INTO teams (id, name, version, created_at, updated_at)
SELECT CAST('0e7e0000-0000-4000-8000-000000000001' AS UUID), 'Открытый набор', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM teams WHERE LOWER(name) = LOWER('Открытый набор'));

INSERT INTO organizations (id, external_key, name, type, team_id, owner_manager_id, version, status, created_at, updated_at)
SELECT CAST('0e7e0000-0000-4000-8000-000000000002' AS UUID), 'open-enrolment', 'Открытый набор (физлица)', 'OPEN_ENROLLMENT', team.id, NULL, 0,
       'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM teams team
WHERE LOWER(team.name) = LOWER('Открытый набор')
  AND NOT EXISTS (
      SELECT 1 FROM organizations
      WHERE external_key = 'open-enrolment' OR LOWER(name) = LOWER('Открытый набор (физлица)')
  );
