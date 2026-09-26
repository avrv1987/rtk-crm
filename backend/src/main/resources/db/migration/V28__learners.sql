CREATE TABLE enrolment_streams (
    id UUID PRIMARY KEY,
    course_key VARCHAR(310) NOT NULL,
    course_name VARCHAR(1333) NOT NULL,
    stream_no INTEGER NOT NULL CHECK (stream_no > 0),
    ends_on DATE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT enrolment_streams_key UNIQUE (course_key, stream_no)
);

CREATE TABLE learners (
    id UUID PRIMARY KEY,
    key_version VARCHAR(16),
    fields TEXT,
    email_hmac CHAR(64),
    phone_hmac CHAR(64),
    snils_hmac CHAR(64),
    name_hmac CHAR(64),
    last_name_hmac CHAR(64),
    missing_fields VARCHAR(600) NOT NULL DEFAULT '',
    personal_data_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (personal_data_status IN ('ACTIVE', 'RESTRICTED', 'ANONYMIZED')),
    anonymized_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT learners_profile_state CHECK (
        (personal_data_status = 'ANONYMIZED' AND anonymized_at IS NOT NULL
            AND fields IS NULL AND key_version IS NULL
            AND email_hmac IS NULL AND phone_hmac IS NULL AND snils_hmac IS NULL
            AND name_hmac IS NULL AND last_name_hmac IS NULL)
        OR (personal_data_status <> 'ANONYMIZED' AND anonymized_at IS NULL
            AND fields IS NOT NULL AND key_version IS NOT NULL)
    )
);
CREATE UNIQUE INDEX learners_snils_key ON learners (snils_hmac) WHERE snils_hmac IS NOT NULL;
CREATE INDEX learners_email_idx ON learners (email_hmac);
CREATE INDEX learners_phone_idx ON learners (phone_hmac);
CREATE INDEX learners_name_idx ON learners (name_hmac);
CREATE INDEX learners_last_name_idx ON learners (last_name_hmac);
CREATE INDEX learners_key_version_idx ON learners (key_version);

CREATE TABLE learner_enrolments (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES learners(id),
    stream_id UUID NOT NULL REFERENCES enrolment_streams(id),
    source_record_id UUID NOT NULL UNIQUE REFERENCES source_records(id),
    lms_export_id UUID,
    lms_exported_at TIMESTAMP WITH TIME ZONE,
    lms_transferred_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT learner_enrolments_learner_stream UNIQUE (learner_id, stream_id),
    CONSTRAINT learner_enrolments_lms CHECK (lms_transferred_at IS NULL OR lms_exported_at IS NOT NULL)
);
CREATE INDEX learner_enrolments_stream_idx ON learner_enrolments (stream_id, lms_transferred_at);
