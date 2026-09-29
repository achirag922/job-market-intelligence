-- V8.5: application intelligence.
--
-- A follow-up date (with an optional short reminder) per saved job, and a history of status
-- changes so the funnel counts what an application actually reached rather than only where it
-- stands now. Both are private to the owning account.

ALTER TABLE saved_jobs
    ADD COLUMN follow_up_on   DATE,
    ADD COLUMN follow_up_note VARCHAR(200),
    ADD CONSTRAINT ck_saved_jobs_follow_up_note CHECK (follow_up_note IS NULL OR follow_up_on IS NOT NULL);

CREATE INDEX idx_saved_jobs_follow_up ON saved_jobs (user_id, follow_up_on) WHERE follow_up_on IS NOT NULL;

CREATE TABLE saved_job_status_events (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY,
    saved_job_id UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    changed_at   TIMESTAMPTZ  NOT NULL,
    -- TRUE for rows reconstructed below whose time is only the row's last update.
    backfilled   BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_saved_job_status_events PRIMARY KEY (id),
    CONSTRAINT fk_saved_job_status_events_saved FOREIGN KEY (saved_job_id) REFERENCES saved_jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_job_status_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_saved_job_status_events_status CHECK (
        status IN ('SAVED', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN'))
);

CREATE INDEX idx_saved_job_status_events_user ON saved_job_status_events (user_id, changed_at);
CREATE INDEX idx_saved_job_status_events_saved ON saved_job_status_events (saved_job_id);

-- What existing rows already record exactly: when each job was saved and first applied to.
INSERT INTO saved_job_status_events (saved_job_id, user_id, status, changed_at)
SELECT id, user_id, 'SAVED', saved_at FROM saved_jobs;

INSERT INTO saved_job_status_events (saved_job_id, user_id, status, changed_at)
SELECT id, user_id, 'APPLIED', applied_at FROM saved_jobs WHERE applied_at IS NOT NULL;

-- A later stage is known to have been reached, but not when; its time is the last update, flagged.
INSERT INTO saved_job_status_events (saved_job_id, user_id, status, changed_at, backfilled)
SELECT id, user_id, status, updated_at, TRUE FROM saved_jobs WHERE status IN ('INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN');
