CREATE TABLE vendor_contacts (
    id UUID PRIMARY KEY,
    vendor_id UUID NOT NULL REFERENCES vendors(id),
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(16),
    email VARCHAR(320),
    prefers_email BOOLEAN NOT NULL DEFAULT FALSE,
    prefers_telegram BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (personal_data_status IN ('ACTIVE', 'RESTRICTED', 'ANONYMIZED')),
    external_key VARCHAR(200) UNIQUE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT vendor_contacts_id_vendor_key UNIQUE (id, vendor_id),
    CONSTRAINT vendor_contacts_phone_check CHECK (phone IS NULL OR phone ~ '^\+7[0-9]{10}$')
);

CREATE UNIQUE INDEX vendor_contacts_vendor_email_key ON vendor_contacts (vendor_id, lower(email)) WHERE email IS NOT NULL;
CREATE INDEX vendor_contacts_vendor_idx ON vendor_contacts (vendor_id, name, id);

ALTER TABLE products ADD COLUMN vendor_contact_id UUID;
ALTER TABLE products ADD CONSTRAINT products_vendor_contact_fk
    FOREIGN KEY (vendor_contact_id, vendor_id) REFERENCES vendor_contacts (id, vendor_id);
CREATE INDEX products_vendor_contact_idx ON products (vendor_contact_id);

ALTER TABLE catalog_imports DROP CONSTRAINT catalog_imports_profile_check;
ALTER TABLE catalog_imports ADD CONSTRAINT catalog_imports_profile_check
    CHECK (profile IN ('AGREEMENT', 'DIRECTION_PROGRAM', 'VENDOR_CONTACTS'));

ALTER TABLE catalog_change_events DROP CONSTRAINT catalog_change_events_entity_type_check;
ALTER TABLE catalog_change_events ADD CONSTRAINT catalog_change_events_entity_type_check CHECK (entity_type IN (
    'ORGANIZATION', 'TEAM', 'DIRECTION', 'PROGRAM', 'VENDOR', 'PRODUCT', 'AGREEMENT', 'VENDOR_CONTACT'
));
