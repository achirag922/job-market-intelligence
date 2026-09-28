-- V8.2: job status and data quality.
--
-- posted_date (V1) is when the source says the posting was published; last_seen_at (V16) is
-- when a run last met it. This adds when the source says it closes, and whether it is still
-- open. Jobs are never deleted for being old: an expired job stays, marked inactive, so
-- history and analytics keep it.
--
-- No new unique constraint is needed: the duplicate keys the ETL matches on are already
-- enforced by uq_jobs_source_job_id (V16, created while every source_job_id was NULL),
-- uq_jobs_source_url and uq_jobs_content_fingerprint (V1), so existing rows cannot hold
-- duplicates that a new constraint would trip over.

ALTER TABLE jobs
    ADD COLUMN expires_at     DATE,
    ADD COLUMN active         BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN deactivated_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_jobs_expiry_after_posting
        CHECK (expires_at IS NULL OR posted_date IS NULL OR expires_at >= posted_date),
    ADD CONSTRAINT ck_jobs_deactivated_when_inactive
        CHECK (active OR deactivated_at IS NOT NULL);

COMMENT ON COLUMN jobs.expires_at IS 'When the source says the posting closes; NULL when it does not say.';
COMMENT ON COLUMN jobs.active IS
    'FALSE once the posting has expired (expires_at passed) or its source marked it closed. Never deleted.';

-- The end-of-run expiry pass looks only at open jobs that have an expiry date.
CREATE INDEX idx_jobs_open_expiry ON jobs (expires_at) WHERE active AND expires_at IS NOT NULL;

-- How many jobs each run marked expired or closed.
ALTER TABLE etl_run_metrics ADD COLUMN jobs_expired BIGINT NOT NULL DEFAULT 0;
