CREATE TABLE directions (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE programs (
    id UUID PRIMARY KEY,
    direction_id UUID NOT NULL REFERENCES directions(id),
    name VARCHAR(200) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT programs_direction_name_key UNIQUE (direction_id, name)
);

CREATE TABLE vendors (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE products (
    id UUID PRIMARY KEY,
    vendor_id UUID NOT NULL REFERENCES vendors(id),
    name VARCHAR(200) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT products_vendor_name_key UNIQUE (vendor_id, name)
);

ALTER TABLE interactions ADD COLUMN program_id UUID REFERENCES programs(id);
ALTER TABLE interactions ADD COLUMN last_contact_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE product_agreements (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id),
    contract_number VARCHAR(200),
    license_signed BOOLEAN,
    license_expiry_year INTEGER,
    transfer_status VARCHAR(160),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX programs_active_name_idx ON programs (archived, name, id);
CREATE INDEX products_active_name_idx ON products (archived, name, id);
CREATE INDEX interactions_program_idx ON interactions (program_id);
CREATE INDEX product_agreements_interaction_product_idx ON product_agreements (interaction_id, product_id, id);
