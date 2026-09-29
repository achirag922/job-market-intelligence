-- V8.4: job alert digests.
--
-- A scheduled pass (never the ETL) looks at each active alert once per its frequency, finds
-- postings first seen since the alert was last processed, and emails one digest. Every job
-- sent is recorded here, once per alert: the unique key is what makes a repeat impossible.

ALTER TABLE job_alerts ADD COLUMN last_processed_at TIMESTAMPTZ;

COMMENT ON COLUMN job_alerts.last_processed_at IS
    'End of the window the last digest pass covered; the next pass looks at postings first seen after it.';

-- Due alerts are active ones by frequency and last pass.
DROP INDEX idx_job_alerts_active_frequency;
CREATE INDEX idx_job_alerts_active_due ON job_alerts (frequency, last_processed_at) WHERE active;

CREATE TABLE job_alert_notifications (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY,
    alert_id          UUID         NOT NULL,
    user_id           UUID         NOT NULL,
    job_id            BIGINT       NOT NULL,
    -- The V8.3 overall match against the user's current resume; NULL without one.
    match_percentage  NUMERIC(4, 1),
    status            VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    attempts          SMALLINT     NOT NULL DEFAULT 0,
    -- The failure's type only, never a message that could carry an address or a credential.
    last_error        VARCHAR(100),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,

    CONSTRAINT pk_job_alert_notifications PRIMARY KEY (id),
    CONSTRAINT fk_job_alert_notifications_alert FOREIGN KEY (alert_id) REFERENCES job_alerts (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_alert_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_alert_notifications_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT uq_job_alert_notifications_alert_job UNIQUE (alert_id, job_id),
    CONSTRAINT ck_job_alert_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_job_alert_notifications_match CHECK (match_percentage BETWEEN 0 AND 100),
    CONSTRAINT ck_job_alert_notifications_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX idx_job_alert_notifications_alert ON job_alert_notifications (alert_id, created_at DESC);
CREATE INDEX idx_job_alert_notifications_user ON job_alert_notifications (user_id);
CREATE INDEX idx_job_alert_notifications_job ON job_alert_notifications (job_id);
-- Digests that still have to go out (pending, or failed and to be retried).
CREATE INDEX idx_job_alert_notifications_unsent ON job_alert_notifications (alert_id) WHERE status <> 'SENT';

-- The alert pass selects postings by when they were first seen.
CREATE INDEX idx_jobs_first_seen ON jobs (first_seen_at DESC);
