CREATE TABLE partner_requests (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    contact_id UUID NOT NULL REFERENCES contacts(id),
    action VARCHAR(300) NOT NULL,
    expected_result VARCHAR(1000) NOT NULL,
    due_on DATE,
    status VARCHAR(24) NOT NULL DEFAULT 'AWAITING_RESPONSE' CHECK (status IN (
        'AWAITING_RESPONSE', 'CLARIFICATION_NEEDED', 'RESPONDED', 'ACCEPTED', 'RETURNED', 'CANCELLED'
    )),
    return_reason VARCHAR(1000),
    clarification VARCHAR(2000),
    cancel_reason VARCHAR(1000),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT partner_requests_return_reason_check CHECK (status <> 'RETURNED' OR return_reason IS NOT NULL)
);
CREATE INDEX partner_requests_interaction_idx ON partner_requests (interaction_id, created_at);
CREATE INDEX partner_requests_contact_idx ON partner_requests (contact_id, status);
CREATE INDEX partner_requests_review_idx ON partner_requests (organization_id, status);

CREATE TABLE partner_request_materials (
    request_id UUID NOT NULL REFERENCES partner_requests(id) ON DELETE CASCADE,
    attachment_id UUID NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    PRIMARY KEY (request_id, attachment_id)
);

CREATE TABLE partner_request_responses (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES partner_requests(id) ON DELETE CASCADE,
    receipt_number VARCHAR(16) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ANSWER', 'QUESTION')),
    message VARCHAR(4000),
    attachment_id UUID REFERENCES attachments(id),
    submitted_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decision VARCHAR(16) CHECK (decision IN ('ACCEPTED', 'RETURNED')),
    decision_reason VARCHAR(1000),
    decided_by UUID REFERENCES crm_user_profiles(id),
    decided_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT partner_request_responses_content_check CHECK (message IS NOT NULL OR attachment_id IS NOT NULL)
);
CREATE INDEX partner_request_responses_request_idx ON partner_request_responses (request_id, submitted_at);

CREATE TABLE partner_material_receipts (
    attachment_id UUID NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (attachment_id, profile_id)
);
