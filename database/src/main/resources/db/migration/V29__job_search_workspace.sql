-- V9.14: the job-search workspace. Everything is per account and removed with it.
--
-- saved_jobs gains an optional application priority and the follow-up date a reminder email was last
-- sent for, so each follow-up date is reminded once. Hidden ("not interested") jobs, recently viewed
-- jobs and saved searches are new, small tables keyed by the owner.

ALTER TABLE saved_jobs
    ADD COLUMN priority              VARCHAR(6),
    ADD COLUMN follow_up_reminded_on DATE,
    ADD CONSTRAINT ck_saved_jobs_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'));

CREATE TABLE hidden_jobs (
    user_id   UUID         NOT NULL,
    job_id    BIGINT       NOT NULL,
    hidden_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_hidden_jobs PRIMARY KEY (user_id, job_id),
    CONSTRAINT fk_hidden_jobs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_hidden_jobs_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE
);

CREATE TABLE job_views (
    user_id   UUID         NOT NULL,
    job_id    BIGINT       NOT NULL,
    viewed_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_job_views PRIMARY KEY (user_id, job_id),
    CONSTRAINT fk_job_views_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_views_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE
);

CREATE INDEX idx_job_views_recent ON job_views (user_id, viewed_at DESC);

CREATE TABLE saved_searches (
    id         UUID          NOT NULL,
    user_id    UUID          NOT NULL,
    name       VARCHAR(80)   NOT NULL,
    filters    JSONB         NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_saved_searches PRIMARY KEY (id),
    CONSTRAINT fk_saved_searches_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_saved_searches_name ON saved_searches (user_id, lower(name));
