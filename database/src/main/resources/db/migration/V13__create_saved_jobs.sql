-- V7.2: jobs an account has saved, and where each application stands.
--
-- One row per (account, job). Removing the account or the job removes the row, so nothing
-- is left pointing at a posting or a user that no longer exists. applied_at is set when the
-- application moves past SAVED and cleared if it is moved back.

CREATE TABLE saved_jobs (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    job_id      BIGINT        NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'SAVED',
    notes       VARCHAR(2000),
    saved_at    TIMESTAMPTZ   NOT NULL,
    applied_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_saved_jobs PRIMARY KEY (id),
    CONSTRAINT fk_saved_jobs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_jobs_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT uq_saved_jobs_user_job UNIQUE (user_id, job_id),
    CONSTRAINT ck_saved_jobs_status CHECK (
        status IN ('SAVED', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_saved_jobs_applied_at CHECK ((status = 'SAVED') = (applied_at IS NULL))
);

-- "My saved jobs", most recently changed first. The unique constraint's index serves
-- lookups by (user, job).
CREATE INDEX idx_saved_jobs_user_updated ON saved_jobs (user_id, updated_at DESC);

-- Lets a job deletion find the rows to cascade to without scanning the table.
CREATE INDEX idx_saved_jobs_job ON saved_jobs (job_id);

COMMENT ON COLUMN saved_jobs.user_id IS
    'Owning account. Set from the authenticated session, never from client input.';
COMMENT ON COLUMN saved_jobs.notes IS 'Private to the owning account.';
