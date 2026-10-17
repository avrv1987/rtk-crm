ALTER TABLE agreements DROP CONSTRAINT agreements_status_check;
ALTER TABLE agreements ADD CONSTRAINT agreements_status_check
    CHECK (status IN ('DRAFT', 'REVIEW', 'CORRECTION', 'SIGNING', 'ACTIVE', 'COMPLETED', 'TERMINATED'));

ALTER TABLE agreements ADD COLUMN sent_on DATE;
ALTER TABLE agreements ADD COLUMN status_changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE agreements SET status_changed_at = updated_at;
ALTER TABLE agreements ADD COLUMN university_signer_id UUID REFERENCES contacts(id);
ALTER TABLE agreements ADD COLUMN school_signer_id UUID REFERENCES crm_user_profiles(id);

CREATE INDEX agreements_status_idx ON agreements (status, status_changed_at);

CREATE TABLE agreement_status_events (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    comment VARCHAR(2000),
    actor_profile_id UUID REFERENCES crm_user_profiles(id),
    interaction_id UUID REFERENCES interactions(id) ON DELETE SET NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX agreement_status_events_agreement_idx ON agreement_status_events (agreement_id, occurred_at, id);
CREATE INDEX agreement_status_events_occurred_idx ON agreement_status_events (occurred_at);

INSERT INTO agreement_status_events (id, agreement_id, from_status, to_status, comment, actor_profile_id, occurred_at)
SELECT gen_random_uuid(), id, NULL, status, 'Статус на момент включения истории', created_by, updated_at
FROM agreements;

CREATE TABLE agreement_interactions (
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    PRIMARY KEY (agreement_id, interaction_id)
);

CREATE INDEX agreement_interactions_interaction_idx ON agreement_interactions (interaction_id);

INSERT INTO agreement_interactions (agreement_id, interaction_id)
SELECT ag.id, a.interaction_id
FROM agreements ag
JOIN attachments a ON a.id = ag.file_attachment_id;

CREATE TABLE agreement_programs (
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    program_id UUID NOT NULL REFERENCES programs(id) ON DELETE CASCADE,
    PRIMARY KEY (agreement_id, program_id)
);

CREATE TABLE agreement_products (
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    PRIMARY KEY (agreement_id, product_id)
);

CREATE TABLE agreement_directions (
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    direction_id UUID NOT NULL REFERENCES directions(id) ON DELETE CASCADE,
    PRIMARY KEY (agreement_id, direction_id)
);

CREATE TABLE agreement_supplements (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    number VARCHAR(100) NOT NULL,
    concluded_on DATE,
    subject VARCHAR(2000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'SIGNED')),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT agreement_supplements_number_key UNIQUE (agreement_id, number)
);

CREATE TABLE agreement_files (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    supplement_id UUID REFERENCES agreement_supplements(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL CHECK (version_number >= 1),
    attachment_id UUID REFERENCES attachments(id) ON DELETE SET NULL,
    storage_key UUID UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    checksum CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('QUARANTINE', 'CLEAN', 'REJECTED', 'UNVERIFIABLE')),
    comment VARCHAR(500),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX agreement_files_version_key
    ON agreement_files (agreement_id, COALESCE(supplement_id, agreement_id), version_number);
CREATE INDEX agreement_files_attachment_idx ON agreement_files (attachment_id);

INSERT INTO agreement_files (
    id, agreement_id, supplement_id, version_number, attachment_id, storage_key, original_name, media_type, size_bytes,
    checksum, status, comment, created_by, created_at
)
SELECT gen_random_uuid(), ag.id, NULL, 1, a.id, NULL, a.original_name, a.media_type, a.size_bytes, a.checksum, a.status,
       'Документ из работы «' || i.title || '»', a.created_by, a.created_at
FROM agreements ag
JOIN attachments a ON a.id = ag.file_attachment_id
JOIN interactions i ON i.id = a.interaction_id;

ALTER TABLE agreements DROP COLUMN file_attachment_id;
