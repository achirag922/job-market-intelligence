-- V8.3: what a user wants from a job, beyond skills, for the match score.
--
-- One row per account, every field optional: a dimension the user leaves empty is shown as
-- unavailable in the match breakdown rather than guessed. Salary is compared only in the
-- currency given here; nothing is converted.

CREATE TABLE match_preferences (
    user_id            UUID          NOT NULL,
    years_experience   SMALLINT,
    preferred_location VARCHAR(200),
    work_mode          VARCHAR(10),
    min_salary         NUMERIC(12, 2),
    salary_currency    CHAR(3),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_match_preferences PRIMARY KEY (user_id),
    CONSTRAINT fk_match_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_match_preferences_years CHECK (years_experience BETWEEN 0 AND 60),
    CONSTRAINT ck_match_preferences_mode CHECK (work_mode IN ('REMOTE', 'HYBRID', 'ON_SITE')),
    CONSTRAINT ck_match_preferences_salary CHECK (min_salary >= 0),
    CONSTRAINT ck_match_preferences_currency CHECK (salary_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_match_preferences_salary_currency CHECK ((min_salary IS NULL) = (salary_currency IS NULL))
);
