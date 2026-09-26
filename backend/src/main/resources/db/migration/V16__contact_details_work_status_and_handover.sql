ALTER TABLE contacts ADD COLUMN decision_role VARCHAR(32)
    CHECK (decision_role IN ('SIGNATORY', 'IMPLEMENTER', 'TEACHER', 'APPROVER', 'OTHER'));
ALTER TABLE contacts ADD COLUMN primary_contact BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE contacts ADD COLUMN inactive BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE contacts ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE contacts ADD COLUMN confirmed_by UUID REFERENCES crm_user_profiles(id);
ALTER TABLE contacts ADD CONSTRAINT contacts_primary_active_check CHECK (NOT (primary_contact AND inactive));
ALTER TABLE contacts ADD CONSTRAINT contacts_confirmation_check CHECK ((confirmed_at IS NULL) = (confirmed_by IS NULL));
CREATE UNIQUE INDEX contacts_primary_organization_key ON contacts (organization_id) WHERE primary_contact;

CREATE TABLE contact_events (
    id UUID PRIMARY KEY,
    contact_id UUID NOT NULL REFERENCES contacts(id),
    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    changes TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version >= 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX contact_events_history_idx ON contact_events (contact_id, occurred_at, id);

ALTER TABLE interactions ADD COLUMN work_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (work_status IN ('ACTIVE', 'PAUSED', 'COMPLETED'));
ALTER TABLE interactions ADD COLUMN work_status_reason VARCHAR(1000);
ALTER TABLE interactions ADD COLUMN waiting_on VARCHAR(16) CHECK (waiting_on IN ('UNIVERSITY', 'RTK'));
ALTER TABLE interactions ADD COLUMN waiting_note VARCHAR(500);
ALTER TABLE interactions ADD COLUMN problem VARCHAR(1000);
ALTER TABLE interactions ADD COLUMN risk_level VARCHAR(16) CHECK (risk_level IN ('MEDIUM', 'HIGH'));
ALTER TABLE interactions ADD COLUMN risk_reason VARCHAR(1000);
ALTER TABLE interactions ADD CONSTRAINT interactions_waiting_note_check CHECK (waiting_on IS NOT NULL OR waiting_note IS NULL);
ALTER TABLE interactions ADD CONSTRAINT interactions_risk_reason_check CHECK ((risk_level IS NULL) = (risk_reason IS NULL));

ALTER TABLE interaction_events DROP CONSTRAINT interaction_events_type_check;
ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_type_check
    CHECK (type IN ('CREATED', 'TRANSITIONED', 'COMMENTED', 'STAGES_EDITED', 'PLAN_UPDATED', 'DETAILS_UPDATED', 'STATUS_CHANGED'));

CREATE TABLE interaction_event_contacts (
    event_id UUID NOT NULL REFERENCES interaction_events(id),
    contact_id UUID NOT NULL REFERENCES contacts(id),
    change_type VARCHAR(16) NOT NULL CHECK (change_type IN ('ADDED', 'REMOVED')),
    PRIMARY KEY (event_id, contact_id)
);

ALTER TABLE organization_assignment_events ADD COLUMN reason VARCHAR(32)
    CHECK (reason IN ('PROFILE_BLOCKED', 'PROFILE_ROLE_CHANGED', 'PROFILE_TEAM_CHANGED', 'ORGANIZATION_TEAM_CHANGED', 'IMPORT'));
ALTER TABLE organization_assignment_events ADD COLUMN handover_note VARCHAR(2000);

UPDATE organization_assignment_events assignment_event
SET reason = CASE
        WHEN NOT profile_event.active THEN 'PROFILE_BLOCKED'
        WHEN profile_event.previous_role <> profile_event.role THEN 'PROFILE_ROLE_CHANGED'
        ELSE 'PROFILE_TEAM_CHANGED'
    END
FROM crm_profile_events profile_event
WHERE profile_event.command_id = assignment_event.command_id
  AND assignment_event.owner_manager_id IS NULL;

UPDATE organization_assignment_events assignment_event
SET reason = 'ORGANIZATION_TEAM_CHANGED'
FROM organization_team_events team_event
WHERE team_event.command_id = assignment_event.command_id
  AND assignment_event.reason IS NULL;

UPDATE organization_assignment_events
SET reason = 'IMPORT'
WHERE request_id LIKE 'catalog-import:%' AND reason IS NULL;
