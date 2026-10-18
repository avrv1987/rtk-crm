ALTER TABLE interaction_events ADD COLUMN work_status_after VARCHAR(16)
    CHECK (work_status_after IN ('ACTIVE', 'PAUSED', 'COMPLETED'));

UPDATE interaction_events
SET work_status_after = CASE
        WHEN comment LIKE 'Взаимодействие завершено%' THEN 'COMPLETED'
        WHEN comment LIKE 'Взаимодействие приостановлено%' THEN 'PAUSED'
        ELSE 'ACTIVE'
    END
WHERE type = 'STATUS_CHANGED';

ALTER TABLE lms_signal_dismissals DROP CONSTRAINT lms_signal_dismissals_signal_type_check;
ALTER TABLE lms_signal_dismissals ADD CONSTRAINT lms_signal_dismissals_signal_type_check
    CHECK (signal_type IN ('STUDENTS_APPEARED', 'NO_STUDENTS', 'LOW_COMPLETION', 'COMPLETION_UNVERIFIED', 'LOW_ACTIVITY',
                           'NOT_STARTED', 'DROPOUT', 'TEACHER_INACTIVE', 'NO_ACTIVITY'));
