ALTER TABLE catalog_imports DROP CONSTRAINT catalog_imports_profile_check;
ALTER TABLE catalog_imports ADD CONSTRAINT catalog_imports_profile_check
    CHECK (profile IN ('AGREEMENT', 'DIRECTION_PROGRAM', 'VENDOR_CONTACTS', 'INTERACTION_HISTORY', 'UNIVERSITY_AGREEMENT'));

ALTER TABLE interaction_events ADD COLUMN import_source VARCHAR(300);
ALTER TABLE interaction_events ADD COLUMN import_author VARCHAR(200);

ALTER TABLE interactions ADD COLUMN external_key VARCHAR(200) UNIQUE;

CREATE TABLE import_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    default_step_text VARCHAR(500) NOT NULL,
    default_step_days INTEGER NOT NULL CHECK (default_step_days BETWEEN 1 AND 365),
    updated_by UUID REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO import_settings (id, default_step_text, default_step_days, updated_by, updated_at)
VALUES (1, 'Связаться с вузом', 7, CAST(NULL AS UUID), CURRENT_TIMESTAMP);
