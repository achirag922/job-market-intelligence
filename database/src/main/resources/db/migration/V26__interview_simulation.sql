-- V9.6: interview simulation. A session has a type, a difficulty and a question count; a question
-- can be skipped, and an evaluation also scores communication and suggests a better approach.

ALTER TABLE interview_sessions
    ADD COLUMN interview_type VARCHAR(10) NOT NULL DEFAULT 'MIXED',
    ADD COLUMN difficulty     VARCHAR(6)  NOT NULL DEFAULT 'MEDIUM',
    ADD CONSTRAINT ck_interview_sessions_type CHECK (interview_type IN ('TECHNICAL', 'BEHAVIORAL', 'MIXED')),
    ADD CONSTRAINT ck_interview_sessions_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD'));

ALTER TABLE interview_questions
    ADD COLUMN communication      SMALLINT,
    ADD COLUMN suggested_approach VARCHAR(1000),
    ADD COLUMN skipped_at         TIMESTAMPTZ,
    DROP CONSTRAINT ck_interview_questions_status,
    ADD CONSTRAINT ck_interview_questions_status
        CHECK (feedback_status IN ('NOT_ANSWERED', 'EVALUATED', 'UNAVAILABLE', 'SKIPPED')),
    ADD CONSTRAINT ck_interview_questions_communication CHECK (communication BETWEEN 1 AND 5),
    ADD CONSTRAINT ck_interview_questions_skipped CHECK ((feedback_status = 'SKIPPED') = (skipped_at IS NOT NULL));
