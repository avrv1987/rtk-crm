ALTER TABLE interaction_events DROP CONSTRAINT interaction_events_type_check;
ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_type_check
    CHECK (type IN ('CREATED', 'TRANSITIONED', 'COMMENTED', 'STAGES_EDITED', 'PLAN_UPDATED',
                    'DETAILS_UPDATED', 'STATUS_CHANGED', 'AGREEMENT_UPDATED', 'ATTACHMENT_DELETED',
                    'STAGE_COMPLETED', 'STAGE_COMPLETION_CLEARED'));

CREATE TABLE interaction_stage_completions (
    interaction_id UUID NOT NULL,
    stage_id UUID NOT NULL,
    completed_on DATE NOT NULL,
    comment TEXT,
    event_id UUID NOT NULL,
    PRIMARY KEY (interaction_id, stage_id),
    FOREIGN KEY (stage_id, interaction_id)
        REFERENCES interaction_stages(id, interaction_id) ON DELETE CASCADE,
    FOREIGN KEY (event_id, interaction_id)
        REFERENCES interaction_events(id, interaction_id) ON DELETE CASCADE
);
