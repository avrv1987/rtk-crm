ALTER TABLE directions ADD COLUMN labor_market BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sources DROP CONSTRAINT sources_code_check;
ALTER TABLE sources ADD CONSTRAINT sources_code_check CHECK (code IN ('WEBSITE', 'MOODLE', 'LABOR_MARKET'));
INSERT INTO sources (code) VALUES ('LABOR_MARKET');

CREATE TABLE labor_market_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    base_url VARCHAR(500),
    token_encrypted VARCHAR(2000),
    token_changed_at TIMESTAMP WITH TIME ZONE,
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE labor_market_snapshots (
    external_code VARCHAR(100) NOT NULL,
    period_month DATE NOT NULL CHECK (EXTRACT(DAY FROM period_month) = 1),
    direction_name VARCHAR(300) NOT NULL,
    open_vacancies INTEGER NOT NULL CHECK (open_vacancies >= 0),
    median_salary INTEGER CHECK (median_salary > 0),
    data_source VARCHAR(200) NOT NULL,
    as_of DATE NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sync_run_id UUID REFERENCES sync_runs(id) ON DELETE SET NULL,
    PRIMARY KEY (external_code, period_month)
);

CREATE TABLE labor_market_mappings (
    direction_id UUID PRIMARY KEY REFERENCES directions(id),
    external_code VARCHAR(100) NOT NULL,
    origin VARCHAR(8) NOT NULL CHECK (origin IN ('AUTO', 'MANUAL')),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_by UUID REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX labor_market_mappings_code_idx ON labor_market_mappings (external_code);
