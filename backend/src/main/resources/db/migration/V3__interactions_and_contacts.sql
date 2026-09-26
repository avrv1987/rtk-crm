CREATE TABLE contacts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(200) NOT NULL,
    position VARCHAR(200),
    email VARCHAR(320),
    phone VARCHAR(50),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE contacts ADD CONSTRAINT contacts_id_organization_key UNIQUE (id, organization_id);
CREATE INDEX contacts_organization_name_idx ON contacts (organization_id, name, id);

CREATE TABLE interactions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    title VARCHAR(200) NOT NULL,
    current_stage_id UUID NOT NULL,
    next_action VARCHAR(500),
    next_action_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE interactions ADD CONSTRAINT interactions_id_organization_key UNIQUE (id, organization_id);

CREATE TABLE interaction_stages (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    stage_order INTEGER NOT NULL CHECK (stage_order >= 0),
    name VARCHAR(200) NOT NULL,
    optional BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT interaction_stages_order_key UNIQUE (interaction_id, stage_order),
    CONSTRAINT interaction_stages_id_interaction_key UNIQUE (id, interaction_id)
);

ALTER TABLE interactions ADD CONSTRAINT interactions_current_stage_fk
    FOREIGN KEY (current_stage_id, id) REFERENCES interaction_stages(id, interaction_id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX interactions_organization_created_idx ON interactions (organization_id, created_at DESC, id);
CREATE INDEX interactions_organization_updated_idx ON interactions (organization_id, updated_at DESC, id);
CREATE INDEX interaction_stages_interaction_order_idx ON interaction_stages (interaction_id, stage_order);

CREATE TABLE interaction_contacts (
    interaction_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    PRIMARY KEY (interaction_id, contact_id),
    FOREIGN KEY (interaction_id, organization_id)
        REFERENCES interactions(id, organization_id) ON DELETE CASCADE,
    FOREIGN KEY (contact_id, organization_id)
        REFERENCES contacts(id, organization_id)
);

CREATE INDEX interaction_contacts_contact_idx ON interaction_contacts (contact_id);

CREATE TABLE command_idempotency_records (
    id UUID PRIMARY KEY,
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT command_idempotency_records_key UNIQUE (actor_profile_id, operation, idempotency_key)
);

CREATE TABLE interaction_events (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    command_id UUID NOT NULL REFERENCES command_idempotency_records(id),
    type VARCHAR(32) NOT NULL CHECK (type IN ('CREATED', 'TRANSITIONED', 'COMMENTED')),
    stage_id UUID NOT NULL,
    stage_name_snapshot VARCHAR(200) NOT NULL,
    from_stage_id UUID,
    from_stage_name_snapshot VARCHAR(200),
    to_stage_id UUID,
    to_stage_name_snapshot VARCHAR(200),
    comment TEXT,
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    owner_manager_id_snapshot UUID,
    version INTEGER NOT NULL CHECK (version >= 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_from_stage_fk
    FOREIGN KEY (from_stage_id, interaction_id) REFERENCES interaction_stages(id, interaction_id);
ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_to_stage_fk
    FOREIGN KEY (to_stage_id, interaction_id) REFERENCES interaction_stages(id, interaction_id);
ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_stage_fk
    FOREIGN KEY (stage_id, interaction_id) REFERENCES interaction_stages(id, interaction_id);

CREATE INDEX interaction_events_history_idx ON interaction_events (interaction_id, occurred_at, id);
