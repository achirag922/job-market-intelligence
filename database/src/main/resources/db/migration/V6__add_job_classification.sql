-- Job classification: which kind of role a posting is, and the evidence for that call.
--
-- No normalised_description column. Normalisation is deterministic, so the normalised
-- text can always be recomputed from description; storing it would duplicate the largest
-- column in the table and add a second thing to keep in step.

ALTER TABLE jobs
    ADD COLUMN job_category VARCHAR(50),
    ADD COLUMN classification_confidence NUMERIC(5, 2),
    ADD COLUMN classified_at TIMESTAMPTZ;

COMMENT ON COLUMN jobs.job_category IS
    'Rule-based category, null until the posting has been classified.';
COMMENT ON COLUMN jobs.classification_confidence IS
    'How strong and how clear-cut the evidence was, 0 to 100. Not a statistical probability.';
COMMENT ON COLUMN jobs.classified_at IS
    'When the posting was last classified, so a reprocessing run can find stale rows.';

ALTER TABLE jobs
    ADD CONSTRAINT ck_jobs_classification_confidence CHECK (
        classification_confidence IS NULL
        OR (classification_confidence >= 0 AND classification_confidence <= 100)
    ),
    -- A category and its confidence are recorded together or not at all; one without the
    -- other is a half-written classification that no caller could interpret.
    ADD CONSTRAINT ck_jobs_classification_complete CHECK (
        (job_category IS NULL AND classification_confidence IS NULL AND classified_at IS NULL)
        OR (job_category IS NOT NULL AND classification_confidence IS NOT NULL AND classified_at IS NOT NULL)
    );

-- Every category analytics query groups or filters on this.
CREATE INDEX idx_jobs_job_category ON jobs (job_category);
-- Finding what still needs classifying, without scanning the whole table.
CREATE INDEX idx_jobs_unclassified ON jobs (id) WHERE job_category IS NULL;


-- Why a posting was put in its category. Normalised rows rather than a JSON blob, so the
-- signals can be queried — "which postings were classified on title alone" is a plain
-- query rather than a document scan.
CREATE TABLE job_classification_signals (
    job_id       BIGINT       NOT NULL,
    signal_type  VARCHAR(20)  NOT NULL,
    signal_value VARCHAR(120) NOT NULL,
    weight       NUMERIC(5, 2) NOT NULL,

    -- The composite key is the whole row, and it makes the same signal impossible to
    -- record twice for one posting, however often reprocessing runs.
    CONSTRAINT pk_job_classification_signals PRIMARY KEY (job_id, signal_type, signal_value),

    CONSTRAINT fk_job_classification_signals_job FOREIGN KEY (job_id)
        REFERENCES jobs (id) ON DELETE CASCADE,

    CONSTRAINT ck_job_classification_signals_type CHECK (
        signal_type IN ('TITLE', 'DESCRIPTION', 'SKILL')
    ),
    CONSTRAINT ck_job_classification_signals_weight CHECK (weight > 0)
);

COMMENT ON TABLE job_classification_signals IS
    'The matched evidence behind jobs.job_category, so a classification can be explained rather than trusted.';
