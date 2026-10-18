CREATE TABLE enrolment_batches (
    id UUID PRIMARY KEY,
    stream_id UUID NOT NULL REFERENCES enrolment_streams(id),
    format VARCHAR(16) NOT NULL CHECK (format IN ('XLSX_TEMPLATE', 'MOODLE_CSV')),
    mode VARCHAR(16) CHECK (mode IN ('PENDING', 'ALL')),
    file_name VARCHAR(400),
    checksum CHAR(64),
    rows_count INTEGER NOT NULL CHECK (rows_count >= 0),
    legacy BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    sent_by UUID REFERENCES crm_user_profiles(id)
);
CREATE INDEX enrolment_batches_stream_idx ON enrolment_batches (stream_id, created_at);

CREATE TABLE enrolment_batch_items (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES enrolment_batches(id),
    row_no INTEGER NOT NULL CHECK (row_no >= 1),
    enrolment_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    learner_version INTEGER,
    result VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (result IN ('PENDING', 'ACCEPTED', 'FAILED')),
    error VARCHAR(500),
    result_source VARCHAR(16) CHECK (result_source IN ('MANUAL', 'PROTOCOL', 'LEGACY', 'API')),
    result_at TIMESTAMP WITH TIME ZONE,
    result_by UUID REFERENCES crm_user_profiles(id),
    CONSTRAINT enrolment_batch_items_row_key UNIQUE (batch_id, row_no),
    CONSTRAINT enrolment_batch_items_enrolment_key UNIQUE (batch_id, enrolment_id),
    CONSTRAINT enrolment_batch_items_error_check CHECK (result <> 'FAILED' OR error IS NOT NULL)
);
CREATE INDEX enrolment_batch_items_enrolment_idx ON enrolment_batch_items (enrolment_id);

ALTER TABLE learner_enrolments ADD COLUMN lms_synced_version INTEGER;
ALTER TABLE learner_enrolments ADD COLUMN lms_reconcile_reason VARCHAR(500);

INSERT INTO enrolment_batches (
    id, stream_id, format, mode, file_name, checksum, rows_count, legacy, created_by, created_at, sent_at, sent_by
)
SELECT e.lms_export_id, e.stream_id, 'XLSX_TEMPLATE', CAST(NULL AS VARCHAR(16)), CAST(NULL AS VARCHAR(400)), CAST(NULL AS CHAR(64)),
       COUNT(*), TRUE, CAST(NULL AS UUID), MAX(e.lms_exported_at), MIN(e.lms_transferred_at), CAST(NULL AS UUID)
FROM learner_enrolments e
WHERE e.lms_export_id IS NOT NULL
GROUP BY e.lms_export_id, e.stream_id;

INSERT INTO enrolment_batch_items (
    id, batch_id, row_no, enrolment_id, learner_id, learner_version, result, error, result_source, result_at, result_by
)
SELECT gen_random_uuid(), e.lms_export_id, ROW_NUMBER() OVER (PARTITION BY e.lms_export_id ORDER BY e.id) + 1, e.id, e.learner_id,
       CAST(NULL AS INTEGER),
       CASE WHEN e.lms_transferred_at IS NULL THEN 'PENDING' ELSE 'ACCEPTED' END,
       CAST(NULL AS VARCHAR(500)),
       CASE WHEN e.lms_transferred_at IS NULL THEN CAST(NULL AS VARCHAR(16)) ELSE 'LEGACY' END,
       e.lms_transferred_at, CAST(NULL AS UUID)
FROM learner_enrolments e
WHERE e.lms_export_id IS NOT NULL;

UPDATE learner_enrolments
SET lms_synced_version = (SELECT l.version FROM learners l WHERE l.id = learner_enrolments.learner_id)
WHERE lms_transferred_at IS NOT NULL;
