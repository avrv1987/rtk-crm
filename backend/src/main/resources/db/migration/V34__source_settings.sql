CREATE TABLE source_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    moodle_base_url VARCHAR(500),
    moodle_token_encrypted VARCHAR(2000),
    moodle_token_changed_at TIMESTAMP WITH TIME ZONE,
    moodle_course_ids VARCHAR(2000) NOT NULL,
    moodle_student_roles VARCHAR(500) NOT NULL,
    moodle_teacher_roles VARCHAR(500) NOT NULL,
    website_base_url VARCHAR(500),
    website_token_encrypted VARCHAR(2000),
    website_token_changed_at TIMESTAMP WITH TIME ZONE,
    sync_cron VARCHAR(100),
    updated_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
