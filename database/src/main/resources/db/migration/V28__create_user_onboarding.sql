-- V9.12: first-time onboarding progress, one row per account.
--
-- Only what onboarding itself owns lives here: whether it was completed or skipped, the target role
-- typed on the profile step (it prefills the career goal) and when the profile and preference steps
-- were saved. The answers themselves go to the existing places: experience, skills and job
-- preferences to match_preferences, the resume to resumes, the goal to career_goals.
--
-- An account without a row has not started onboarding (PENDING). Accounts that existed before this
-- migration are marked COMPLETED, so nobody already using JMIP is sent through it.

CREATE TABLE user_onboarding (
    user_id                  UUID          NOT NULL,
    status                   VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    target_role              VARCHAR(100),
    profile_completed_at     TIMESTAMPTZ,
    preferences_completed_at TIMESTAMPTZ,
    skipped_at               TIMESTAMPTZ,
    completed_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL,
    updated_at               TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_user_onboarding PRIMARY KEY (user_id),
    CONSTRAINT fk_user_onboarding_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_onboarding_status CHECK (status IN ('PENDING', 'SKIPPED', 'COMPLETED')),
    CONSTRAINT ck_user_onboarding_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

INSERT INTO user_onboarding (user_id, status, completed_at, created_at, updated_at)
SELECT id, 'COMPLETED', now(), now(), now() FROM users;
