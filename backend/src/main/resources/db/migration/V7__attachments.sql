CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    stage_id UUID NOT NULL,
    event_id UUID,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    storage_key UUID NOT NULL UNIQUE,
    checksum CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('QUARANTINE', 'CLEAN', 'REJECTED', 'UNVERIFIABLE')),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attachments_stage_fk
        FOREIGN KEY (stage_id, interaction_id) REFERENCES interaction_stages(id, interaction_id)
);

ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_id_interaction_key UNIQUE (id, interaction_id);

ALTER TABLE attachments ADD CONSTRAINT attachments_event_fk
    FOREIGN KEY (event_id, interaction_id) REFERENCES interaction_events(id, interaction_id);

CREATE INDEX attachments_interaction_created_idx ON attachments (interaction_id, created_at, id);
CREATE INDEX attachments_event_idx ON attachments (event_id);
