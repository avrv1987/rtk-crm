CREATE TABLE lms_signal_dismissals (
    interaction_id UUID NOT NULL REFERENCES interactions(id) ON DELETE CASCADE,
    mapping_id UUID NOT NULL REFERENCES source_mappings(id) ON DELETE CASCADE,
    signal_type VARCHAR(32) NOT NULL CHECK (signal_type IN ('STUDENTS_APPEARED', 'NO_STUDENTS', 'LOW_COMPLETION')),
    data_version VARCHAR(80) NOT NULL,
    dismissed_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    dismissed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (interaction_id, mapping_id, signal_type)
);
