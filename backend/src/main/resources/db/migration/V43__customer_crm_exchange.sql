ALTER TABLE sources DROP CONSTRAINT IF EXISTS sources_code_check;
ALTER TABLE sources ADD CONSTRAINT sources_code_check CHECK (code IN ('WEBSITE', 'MOODLE', 'LABOR_MARKET', 'CUSTOMER_CRM'));
INSERT INTO sources (code) VALUES ('CUSTOMER_CRM');

ALTER TABLE source_settings ADD COLUMN crm_base_url VARCHAR(500);
ALTER TABLE source_settings ADD COLUMN crm_token_encrypted VARCHAR(2000);
ALTER TABLE source_settings ADD COLUMN crm_token_changed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE source_records ADD COLUMN contact_id UUID REFERENCES contacts(id);
CREATE INDEX source_records_contact_idx ON source_records (contact_id) WHERE contact_id IS NOT NULL;

CREATE TABLE crm_exchange_events (
    id UUID PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('STAGE_CHANGED', 'NEXT_STEP_CHANGED', 'MANAGER_CHANGED', 'AGREEMENT_STATUS')),
    organization_id UUID NOT NULL,
    interaction_id UUID,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(8) NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'ERROR')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(500),
    batch_id UUID,
    sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX crm_exchange_events_due_idx ON crm_exchange_events (status, next_attempt_at, occurred_at);
CREATE INDEX crm_exchange_events_occurred_idx ON crm_exchange_events (occurred_at DESC, id);
