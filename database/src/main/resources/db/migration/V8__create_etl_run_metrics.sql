-- V6.5: the per-run counters Spring Batch does not keep.
--
-- Status, start and end times, and read, write and skip counts already live in the
-- BATCH_* tables and are not repeated here. Only what the framework cannot know is
-- stored: how many postings were actually inserted and how many were duplicates of
-- postings already loaded. One row per Spring Batch job execution, written when the
-- run finishes.

CREATE TABLE etl_run_metrics (
    job_execution_id    BIGINT      NOT NULL,
    records_loaded      BIGINT      NOT NULL,
    duplicates_skipped  BIGINT      NOT NULL,
    skill_links_created BIGINT      NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_etl_run_metrics PRIMARY KEY (job_execution_id),
    CONSTRAINT fk_etl_run_metrics_execution FOREIGN KEY (job_execution_id)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID) ON DELETE CASCADE
);
