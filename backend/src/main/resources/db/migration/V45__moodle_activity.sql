ALTER TABLE learning_snapshots
    ADD COLUMN active_7d_count INTEGER CHECK (active_7d_count >= 0),
    ADD COLUMN active_30d_count INTEGER CHECK (active_30d_count >= 0),
    ADD COLUMN never_accessed_count INTEGER CHECK (never_accessed_count >= 0),
    ADD COLUMN inactive_count INTEGER CHECK (inactive_count >= 0),
    ADD COLUMN teachers_active_7d_count INTEGER CHECK (teachers_active_7d_count >= 0),
    ADD COLUMN teachers_active_14d_count INTEGER CHECK (teachers_active_14d_count >= 0);

ALTER TABLE learning_observations
    ADD COLUMN active_7d_count INTEGER CHECK (active_7d_count >= 0),
    ADD COLUMN active_30d_count INTEGER CHECK (active_30d_count >= 0),
    ADD COLUMN never_accessed_count INTEGER CHECK (never_accessed_count >= 0),
    ADD COLUMN inactive_count INTEGER CHECK (inactive_count >= 0),
    ADD COLUMN teachers_active_7d_count INTEGER CHECK (teachers_active_7d_count >= 0),
    ADD COLUMN teachers_active_14d_count INTEGER CHECK (teachers_active_14d_count >= 0);

ALTER TABLE lms_signal_dismissals DROP CONSTRAINT lms_signal_dismissals_signal_type_check;
ALTER TABLE lms_signal_dismissals ADD CONSTRAINT lms_signal_dismissals_signal_type_check
    CHECK (signal_type IN ('STUDENTS_APPEARED', 'NO_STUDENTS', 'LOW_COMPLETION', 'LOW_ACTIVITY', 'NOT_STARTED', 'DROPOUT',
                           'TEACHER_INACTIVE', 'NO_ACTIVITY'));
ALTER TABLE lms_signal_dismissals ADD COLUMN recorded_as_risk BOOLEAN NOT NULL DEFAULT FALSE;
