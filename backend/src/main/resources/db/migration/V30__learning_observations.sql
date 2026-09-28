CREATE TABLE learning_observations (
    mapping_id UUID NOT NULL REFERENCES source_mappings(id) ON DELETE CASCADE,
    observed_from TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    participants_count INTEGER NOT NULL CHECK (participants_count >= 0),
    teachers_count INTEGER NOT NULL CHECK (teachers_count >= 0),
    completed_count INTEGER CHECK (completed_count >= 0),
    not_completed_count INTEGER CHECK (not_completed_count >= 0),
    unknown_count INTEGER NOT NULL CHECK (unknown_count >= 0),
    groups_count INTEGER NOT NULL CHECK (groups_count >= 0),
    demo BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (mapping_id, observed_from),
    CONSTRAINT learning_observations_completion CHECK ((completed_count IS NULL) = (not_completed_count IS NULL)),
    CONSTRAINT learning_observations_confirmed CHECK (confirmed_at >= observed_from)
);

INSERT INTO learning_observations (
    mapping_id, observed_from, confirmed_at, participants_count, teachers_count, completed_count, not_completed_count,
    unknown_count, groups_count
)
SELECT mapping_id, changed_at, GREATEST(observed_at, changed_at), participants_count, teachers_count, completed_count,
       not_completed_count, unknown_count, groups_count
FROM learning_snapshots;
