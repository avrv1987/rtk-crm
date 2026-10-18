package ru.rtk.crm.enrolment;

public final class EnrolmentBatchTestSchema {

    public static final String BATCHES = """
            CREATE TABLE IF NOT EXISTS enrolment_batches (
                id UUID PRIMARY KEY, stream_id UUID NOT NULL, format VARCHAR(16) NOT NULL, mode VARCHAR(16), file_name VARCHAR(400),
                checksum CHAR(64), rows_count INTEGER NOT NULL, legacy BOOLEAN DEFAULT FALSE NOT NULL, created_by UUID,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL, sent_at TIMESTAMP WITH TIME ZONE, sent_by UUID
            )
            """;

    public static final String ITEMS = """
            CREATE TABLE IF NOT EXISTS enrolment_batch_items (
                id UUID PRIMARY KEY, batch_id UUID NOT NULL, row_no INTEGER NOT NULL, enrolment_id UUID NOT NULL, learner_id UUID NOT NULL,
                learner_version INTEGER, result VARCHAR(16) DEFAULT 'PENDING' NOT NULL, error VARCHAR(500), result_source VARCHAR(16),
                result_at TIMESTAMP WITH TIME ZONE, result_by UUID,
                CONSTRAINT enrolment_batch_items_row_key UNIQUE (batch_id, row_no),
                CONSTRAINT enrolment_batch_items_enrolment_key UNIQUE (batch_id, enrolment_id),
                CONSTRAINT enrolment_batch_items_error_check CHECK (result <> 'FAILED' OR error IS NOT NULL)
            )
            """;

    private EnrolmentBatchTestSchema() {
    }
}
