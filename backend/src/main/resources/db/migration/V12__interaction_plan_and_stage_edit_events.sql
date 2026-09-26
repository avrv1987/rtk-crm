ALTER TABLE interaction_events DROP CONSTRAINT interaction_events_type_check;
ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_type_check
    CHECK (type IN ('CREATED', 'TRANSITIONED', 'COMMENTED', 'STAGES_EDITED', 'PLAN_UPDATED'));

ALTER TABLE interaction_events ADD COLUMN plan_changed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE interaction_events ADD COLUMN next_action VARCHAR(500);
ALTER TABLE interaction_events ADD COLUMN next_action_at TIMESTAMP WITH TIME ZONE;
