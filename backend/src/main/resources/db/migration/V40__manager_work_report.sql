ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_kind_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_kind_check
    CHECK (kind IN ('PORTFOLIO', 'EVENTS', 'DEMAND', 'SNAPSHOT', 'DURATION', 'AGREEMENTS', 'MANAGER_WORK'));
