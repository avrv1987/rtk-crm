ALTER TABLE attachments ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'OTHER';
ALTER TABLE attachments ADD CONSTRAINT attachments_kind_check CHECK (kind IN (
    'CONTRACT', 'LICENSE_AGREEMENT', 'APPENDIX', 'ACT', 'SIGNED_SCAN',
    'MATERIALS', 'DOCUMENTATION', 'CURRICULUM', 'QUALIFICATION', 'OTHER'
));
ALTER TABLE attachments ADD COLUMN revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1);
ALTER TABLE attachments ADD COLUMN replaces_id UUID REFERENCES attachments(id);
ALTER TABLE attachments ADD COLUMN version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE attachments ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE attachments ADD COLUMN deleted_by UUID REFERENCES crm_user_profiles(id);
ALTER TABLE attachments ADD CONSTRAINT attachments_deletion_check CHECK ((deleted_at IS NULL) = (deleted_by IS NULL));

CREATE UNIQUE INDEX attachments_live_replacement_key ON attachments (replaces_id)
    WHERE replaces_id IS NOT NULL AND deleted_at IS NULL AND status IN ('QUARANTINE', 'CLEAN');

ALTER TABLE product_agreements ADD COLUMN scan_attachment_id UUID REFERENCES attachments(id);

CREATE TABLE product_transfers (
    agreement_id UUID NOT NULL REFERENCES product_agreements(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('MATERIALS', 'LICENSE', 'DOCUMENTATION')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('TRANSFERRED', 'NOT_TRANSFERRED')),
    transferred_on DATE,
    attachment_id UUID REFERENCES attachments(id),
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (agreement_id, kind),
    CONSTRAINT product_transfers_state_check CHECK (
        (status = 'TRANSFERRED' AND transferred_on IS NOT NULL)
        OR (status = 'NOT_TRANSFERRED' AND transferred_on IS NULL AND attachment_id IS NULL)
    )
);

CREATE INDEX product_agreements_expiry_idx ON product_agreements (license_expiry_year);

ALTER TABLE interaction_events DROP CONSTRAINT interaction_events_type_check;
ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_type_check
    CHECK (type IN ('CREATED', 'TRANSITIONED', 'COMMENTED', 'STAGES_EDITED', 'PLAN_UPDATED',
                    'DETAILS_UPDATED', 'STATUS_CHANGED', 'AGREEMENT_UPDATED', 'ATTACHMENT_DELETED'));
