-- V7.1: saved job-search alerts, one account each.
--
-- The criteria are the job search's own filters (keywords = the search's q), so a later
-- notification job can run an alert as an ordinary search. Nothing is sent yet: frequency
-- only records how often the user wants to hear about new matches.

CREATE TABLE job_alerts (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    keywords    VARCHAR(200),
    category    VARCHAR(50),
    location    VARCHAR(200),
    experience  VARCHAR(20),
    skill       VARCHAR(100),
    frequency   VARCHAR(10)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_job_alerts PRIMARY KEY (id),
    CONSTRAINT fk_job_alerts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_job_alerts_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_job_alerts_frequency CHECK (frequency IN ('DAILY', 'WEEKLY')),
    CONSTRAINT ck_job_alerts_experience CHECK (experience IN ('0-2', '2-5', '5-8', '8+', 'unspecified')),
    -- An alert with no criteria would match every posting.
    CONSTRAINT ck_job_alerts_has_criteria CHECK (
        keywords IS NOT NULL OR category IS NOT NULL OR location IS NOT NULL
        OR experience IS NOT NULL OR skill IS NOT NULL)
);

-- "My alerts", newest first.
CREATE INDEX idx_job_alerts_user ON job_alerts (user_id, created_at DESC);

-- For the future notification run: the active alerts due at a given frequency.
CREATE INDEX idx_job_alerts_active_frequency ON job_alerts (frequency) WHERE active;

COMMENT ON COLUMN job_alerts.user_id IS
    'Owning account. Set from the authenticated session, never from client input.';
