ALTER TABLE organizations DROP CONSTRAINT IF EXISTS organizations_type_check;
ALTER TABLE organizations ADD CONSTRAINT organizations_type_check
    CHECK (type IN ('UNIVERSITY', 'SCHOOL', 'COLLEGE'));
ALTER TABLE organizations ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE organizations ADD CONSTRAINT organizations_status_check
    CHECK (status IN ('ACTIVE', 'PENDING', 'ARCHIVED'));
ALTER TABLE organizations ADD COLUMN city VARCHAR(200);
ALTER TABLE organizations ADD COLUMN website VARCHAR(300);
ALTER TABLE organizations ADD COLUMN inn VARCHAR(12);
CREATE INDEX organizations_status_name_idx ON organizations (status, name, id);

ALTER TABLE teams ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE teams ADD COLUMN default_workflow_template_id UUID
    REFERENCES workflow_templates(id) ON DELETE SET NULL;

ALTER TABLE product_agreements ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE catalog_change_events (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(16) NOT NULL CHECK (entity_type IN (
        'ORGANIZATION', 'TEAM', 'DIRECTION', 'PROGRAM', 'VENDOR', 'PRODUCT', 'AGREEMENT'
    )),
    entity_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN (
        'CREATE', 'REQUEST', 'APPROVE', 'REJECT', 'UPDATE', 'ARCHIVE', 'RESTORE'
    )),
    entity_name VARCHAR(300) NOT NULL,
    changes VARCHAR(2000),
    actor_profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    actor_display_name VARCHAR(200) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX catalog_change_events_occurred_idx ON catalog_change_events (occurred_at DESC, id);
CREATE INDEX catalog_change_events_entity_idx ON catalog_change_events (entity_type, entity_id, occurred_at DESC);

CREATE TABLE catalog_name_locks (
    entity_type VARCHAR(16) PRIMARY KEY CHECK (entity_type IN ('ORGANIZATION', 'DIRECTION', 'PROGRAM', 'VENDOR', 'PRODUCT'))
);
INSERT INTO catalog_name_locks (entity_type) VALUES ('ORGANIZATION'), ('DIRECTION'), ('PROGRAM'), ('VENDOR'), ('PRODUCT');
