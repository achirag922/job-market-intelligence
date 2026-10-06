-- V7.3: several resumes per account, told apart by a title and an optional version label,
-- with at most one marked as the account's default.

ALTER TABLE resumes
    ADD COLUMN title         VARCHAR(100),
    ADD COLUMN version_label VARCHAR(50),
    ADD COLUMN is_default    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN updated_at    TIMESTAMPTZ;

-- Existing resumes are titled after their file (without .pdf) and were last changed when
-- they were processed.
UPDATE resumes
SET title      = left(regexp_replace(original_file_name, '\.pdf$', '', 'i'), 100),
    updated_at = coalesce(processed_at, uploaded_at);

UPDATE resumes SET title = 'Resume' WHERE btrim(title) = '';

-- Each account's most recent upload becomes its default, as it is what the UI showed so far.
UPDATE resumes r
SET is_default = TRUE
WHERE r.user_id IS NOT NULL
  AND r.id = (SELECT newest.id FROM resumes newest
              WHERE newest.user_id = r.user_id
              ORDER BY newest.uploaded_at DESC, newest.id
              LIMIT 1);

ALTER TABLE resumes
    ALTER COLUMN title SET NOT NULL,
    -- The application always sets a title; the default keeps direct inserts valid.
    ALTER COLUMN title SET DEFAULT 'Resume',
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ADD CONSTRAINT ck_resumes_title CHECK (length(btrim(title)) > 0);

-- One default per account; any number of non-default resumes.
CREATE UNIQUE INDEX uq_resumes_default_per_user ON resumes (user_id) WHERE is_default;

-- "My resumes", newest first.
CREATE INDEX idx_resumes_user_uploaded ON resumes (user_id, uploaded_at DESC);
