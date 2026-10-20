CREATE TABLE demand_index_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    growth_weight INTEGER NOT NULL CHECK (growth_weight BETWEEN 0 AND 100),
    completion_weight INTEGER NOT NULL CHECK (completion_weight BETWEEN 0 AND 100),
    activity_weight INTEGER NOT NULL CHECK (activity_weight BETWEEN 0 AND 100),
    university_weight INTEGER NOT NULL CHECK (university_weight BETWEEN 0 AND 100),
    payments_weight INTEGER NOT NULL CHECK (payments_weight BETWEEN 0 AND 100),
    min_coverage_percent INTEGER NOT NULL CHECK (min_coverage_percent BETWEEN 1 AND 100),
    small_base INTEGER NOT NULL CHECK (small_base BETWEEN 0 AND 10000),
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT demand_index_settings_total CHECK (
        growth_weight + completion_weight + activity_weight + university_weight + payments_weight = 100
    )
);
