ALTER TABLE directions ADD COLUMN external_key VARCHAR(200) UNIQUE;
ALTER TABLE programs ADD COLUMN external_key VARCHAR(200) UNIQUE;
ALTER TABLE vendors ADD COLUMN external_key VARCHAR(200) UNIQUE;
ALTER TABLE products ADD COLUMN external_key VARCHAR(200) UNIQUE;
ALTER TABLE organizations ADD COLUMN external_key VARCHAR(200) UNIQUE;
ALTER TABLE contacts ADD COLUMN external_key VARCHAR(200) UNIQUE;
ALTER TABLE product_agreements ADD COLUMN external_key VARCHAR(200) UNIQUE;
ALTER TABLE product_agreements ADD COLUMN version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE interaction_events ADD COLUMN external_key VARCHAR(200) UNIQUE;

CREATE TABLE catalog_imports (
    id UUID PRIMARY KEY,
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    profile VARCHAR(32) NOT NULL CHECK (profile IN ('AGREEMENT', 'DIRECTION_PROGRAM')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PREVIEWED', 'APPLIED')),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    mapping_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE catalog_import_rows (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES catalog_imports(id) ON DELETE CASCADE,
    sheet_name VARCHAR(255) NOT NULL,
    row_number INTEGER NOT NULL CHECK (row_number > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CREATE', 'UPDATE', 'UNCHANGED', 'CONFLICT', 'INVALID')),
    plan_json TEXT NOT NULL,
    errors_json TEXT NOT NULL,
    applied BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT catalog_import_rows_import_row_key UNIQUE (import_id, row_number)
);

CREATE TABLE catalog_import_jobs (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES catalog_imports(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    action VARCHAR(16) NOT NULL CHECK (action IN ('PREVIEW', 'APPLY')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    result_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX catalog_import_rows_import_idx ON catalog_import_rows (import_id, row_number);
CREATE INDEX catalog_import_jobs_import_idx ON catalog_import_jobs (import_id, created_at, id);
