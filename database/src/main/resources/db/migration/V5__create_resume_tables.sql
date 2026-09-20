-- Uploaded resumes and the skills extracted from them.
--
-- The primary key is a UUID rather than a sequence. Resumes are personal documents and
-- the API has no authentication yet, so a sequential id would let anyone walk through
-- other people's uploads by incrementing a number. The same UUID names the stored file,
-- which means the original filename never reaches the filesystem.

CREATE TABLE resumes (
    id                 UUID         NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    stored_file_name   VARCHAR(255) NOT NULL,
    content_type       VARCHAR(100) NOT NULL,
    file_size_bytes    BIGINT       NOT NULL,
    processing_status  VARCHAR(20)  NOT NULL,
    -- Kept so that skill extraction can be re-run, and improved, without asking the user
    -- to upload the document again.
    extracted_text     TEXT,
    error_message      TEXT,
    uploaded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,

    CONSTRAINT pk_resumes PRIMARY KEY (id),
    CONSTRAINT uq_resumes_stored_file_name UNIQUE (stored_file_name),

    CONSTRAINT ck_resumes_processing_status CHECK (
        processing_status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_resumes_file_size CHECK (file_size_bytes > 0),

    -- A failure must say why, and a success must not carry a stale error from an earlier
    -- attempt. This is the invariant that keeps the status column trustworthy.
    CONSTRAINT ck_resumes_error_message CHECK (
        (processing_status = 'FAILED' AND error_message IS NOT NULL)
        OR (processing_status <> 'FAILED' AND error_message IS NULL)
    )
);

COMMENT ON COLUMN resumes.stored_file_name IS
    'Name on disk, derived from the id. The uploaded filename is never used as a path.';
COMMENT ON COLUMN resumes.original_file_name IS
    'What the user called the file. Shown back to them, never used to build a path.';

-- Listing recent uploads.
CREATE INDEX idx_resumes_uploaded_at ON resumes (uploaded_at DESC);


-- Skills found in a resume. Reuses the skills table rather than introducing a second
-- vocabulary, so a resume skill and a job skill are the same row and can be compared
-- directly by id.
CREATE TABLE resume_skills (
    resume_id UUID   NOT NULL,
    skill_id  BIGINT NOT NULL,

    -- The composite key is the whole row, and it makes a duplicate skill impossible.
    CONSTRAINT pk_resume_skills PRIMARY KEY (resume_id, skill_id),

    CONSTRAINT fk_resume_skills_resume FOREIGN KEY (resume_id)
        REFERENCES resumes (id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_skills_skill FOREIGN KEY (skill_id)
        REFERENCES skills (id) ON DELETE CASCADE
);

-- The primary key already serves lookups by resume. Asking which resumes hold a skill
-- reads the other way round.
CREATE INDEX idx_resume_skills_skill ON resume_skills (skill_id);
