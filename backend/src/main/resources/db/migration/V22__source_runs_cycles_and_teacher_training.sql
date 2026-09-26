ALTER TABLE source_mappings DROP CONSTRAINT source_mappings_key;
CREATE UNIQUE INDEX source_mappings_reference_key ON source_mappings (source, kind, external_key)
    WHERE kind IN ('ORGANIZATION', 'PROGRAM');
CREATE INDEX source_mappings_external_key_idx ON source_mappings (source, kind, external_key);
ALTER TABLE source_mappings ADD COLUMN run_kind VARCHAR(16) NOT NULL DEFAULT 'STUDENTS'
    CHECK (run_kind IN ('STUDENTS', 'TEACHERS'));
ALTER TABLE source_mappings ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE learning_snapshots ADD COLUMN mapping_id UUID REFERENCES source_mappings(id) ON DELETE CASCADE;
ALTER TABLE learning_snapshots ADD COLUMN interaction_id UUID REFERENCES interactions(id);
UPDATE learning_snapshots s SET mapping_id = (
    SELECT m.id
    FROM source_records r
    JOIN source_mappings m ON m.source = r.source AND m.external_key = r.external_id
        AND m.kind = CASE WHEN s.group_id IS NULL THEN 'COURSE' ELSE 'GROUP' END
    WHERE r.id = s.source_record_id
), interaction_id = (SELECT r.interaction_id FROM source_records r WHERE r.id = s.source_record_id);
DELETE FROM learning_snapshots WHERE mapping_id IS NULL;
ALTER TABLE learning_snapshots DROP CONSTRAINT learning_snapshots_pkey;
ALTER TABLE learning_snapshots ALTER COLUMN mapping_id SET NOT NULL;
ALTER TABLE learning_snapshots ADD PRIMARY KEY (mapping_id);
CREATE INDEX learning_snapshots_record_idx ON learning_snapshots (source_record_id);

ALTER TABLE sync_runs ADD COLUMN run_trigger VARCHAR(16) NOT NULL DEFAULT 'MANUAL'
    CHECK (run_trigger IN ('MANUAL', 'SCHEDULE', 'CARD', 'BOOTSTRAP'));
ALTER TABLE sync_runs ADD COLUMN organization_id UUID REFERENCES organizations(id);
CREATE INDEX sync_runs_organization_idx ON sync_runs (organization_id, source, created_at DESC);

CREATE INDEX command_idempotency_records_operation_key_idx ON command_idempotency_records (operation, idempotency_key);

CREATE TABLE interaction_cycles (
    interaction_id UUID PRIMARY KEY REFERENCES interactions(id),
    previous_interaction_id UUID NOT NULL UNIQUE REFERENCES interactions(id),
    starts_on DATE NOT NULL,
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT interaction_cycles_not_self CHECK (interaction_id <> previous_interaction_id)
);

CREATE TABLE teacher_trainings (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL REFERENCES interactions(id),
    event_id UUID NOT NULL UNIQUE REFERENCES interaction_events(id),
    trained_on DATE NOT NULL,
    course_name VARCHAR(300) NOT NULL,
    enrolled_count INTEGER NOT NULL CHECK (enrolled_count >= 0),
    completed_count INTEGER CHECK (completed_count >= 0),
    attachment_id UUID REFERENCES attachments(id),
    next_cycle_on DATE,
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT teacher_trainings_completed CHECK (completed_count IS NULL OR completed_count <= enrolled_count)
);

CREATE INDEX teacher_trainings_interaction_idx ON teacher_trainings (interaction_id, trained_on DESC);
