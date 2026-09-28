-- V8.7: interview preparation sessions, private to the owning account.
--
-- Questions are generated from the job's and the resume's own data when a session starts;
-- answers are evaluated by the configured AI provider. The job's title and company are kept
-- so a session stays readable if the posting is later removed.

CREATE TABLE interview_sessions (
    id               UUID          NOT NULL,
    user_id          UUID          NOT NULL,
    job_id           BIGINT,
    resume_id        UUID,
    job_title        VARCHAR(300)  NOT NULL,
    company_name     VARCHAR(255)  NOT NULL,
    status           VARCHAR(12)   NOT NULL DEFAULT 'IN_PROGRESS',
    summary          VARCHAR(2000),
    average_score    NUMERIC(3, 1),
    created_at       TIMESTAMPTZ   NOT NULL,
    completed_at     TIMESTAMPTZ,

    CONSTRAINT pk_interview_sessions PRIMARY KEY (id),
    CONSTRAINT fk_interview_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_sessions_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE SET NULL,
    CONSTRAINT fk_interview_sessions_resume FOREIGN KEY (resume_id) REFERENCES resumes (id) ON DELETE SET NULL,
    CONSTRAINT ck_interview_sessions_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_interview_sessions_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_interview_sessions_score CHECK (average_score BETWEEN 1 AND 5)
);

CREATE INDEX idx_interview_sessions_user ON interview_sessions (user_id, created_at DESC);
CREATE INDEX idx_interview_sessions_job ON interview_sessions (job_id);
CREATE INDEX idx_interview_sessions_resume ON interview_sessions (resume_id);

CREATE TABLE interview_questions (
    id                    BIGINT        GENERATED ALWAYS AS IDENTITY,
    session_id            UUID          NOT NULL,
    position              SMALLINT      NOT NULL,
    category              VARCHAR(12)   NOT NULL,
    question              VARCHAR(600)  NOT NULL,
    -- The skill or posting term the question is about, when it has one.
    focus                 VARCHAR(200),
    answer                VARCHAR(4000),
    answered_at           TIMESTAMPTZ,
    feedback_status       VARCHAR(12)   NOT NULL DEFAULT 'NOT_ANSWERED',
    evaluation_attempts   SMALLINT      NOT NULL DEFAULT 0,
    relevance             SMALLINT,
    completeness          SMALLINT,
    clarity               SMALLINT,
    technical_correctness SMALLINT,
    strengths             VARCHAR(1000),
    improvements          VARCHAR(1000),
    evaluated_at          TIMESTAMPTZ,

    CONSTRAINT pk_interview_questions PRIMARY KEY (id),
    CONSTRAINT fk_interview_questions_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_questions_position UNIQUE (session_id, position),
    CONSTRAINT ck_interview_questions_category CHECK (category IN ('TECHNICAL', 'ROLE', 'RESUME', 'BEHAVIORAL')),
    CONSTRAINT ck_interview_questions_status CHECK (feedback_status IN ('NOT_ANSWERED', 'EVALUATED', 'UNAVAILABLE')),
    CONSTRAINT ck_interview_questions_scores CHECK (
        relevance BETWEEN 1 AND 5 AND completeness BETWEEN 1 AND 5 AND clarity BETWEEN 1 AND 5
        AND technical_correctness BETWEEN 1 AND 5),
    CONSTRAINT ck_interview_questions_evaluated CHECK ((feedback_status = 'EVALUATED') = (relevance IS NOT NULL))
);
