ALTER TABLE source_mappings ADD COLUMN run_starts_on DATE;
ALTER TABLE source_mappings ADD COLUMN run_ends_on DATE;
ALTER TABLE source_mappings ADD CONSTRAINT source_mappings_run_dates CHECK (
    (run_starts_on IS NULL AND run_ends_on IS NULL)
    OR (kind IN ('COURSE', 'GROUP') AND run_starts_on IS NOT NULL AND run_ends_on IS NOT NULL AND run_starts_on < run_ends_on)
);

ALTER TABLE learning_snapshots DROP COLUMN parallel_runs;
