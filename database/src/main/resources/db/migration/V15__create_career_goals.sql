-- V7.4: career goals, the skills a user chose to develop for each, and their progress on
-- the goal's roadmap. Everything belongs to one account and goes with it.

CREATE TABLE career_goals (
    id                UUID         NOT NULL,
    user_id           UUID         NOT NULL,
    target_role       VARCHAR(100) NOT NULL,
    -- A V4 job category; the roadmap reads its skill demand from postings in it.
    target_category   VARCHAR(50)  NOT NULL,
    target_location   VARCHAR(200),
    target_experience VARCHAR(20),
    status            VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_career_goals PRIMARY KEY (id),
    CONSTRAINT fk_career_goals_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_career_goals_role CHECK (length(btrim(target_role)) > 0),
    CONSTRAINT ck_career_goals_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'ARCHIVED')),
    CONSTRAINT ck_career_goals_experience CHECK (target_experience IN ('0-2', '2-5', '5-8', '8+'))
);

CREATE INDEX idx_career_goals_user ON career_goals (user_id, updated_at DESC);

-- Skills the user added to the goal themselves, on top of what the market asks for.
CREATE TABLE career_goal_skills (
    goal_id  UUID   NOT NULL,
    skill_id BIGINT NOT NULL,

    CONSTRAINT pk_career_goal_skills PRIMARY KEY (goal_id, skill_id),
    CONSTRAINT fk_career_goal_skills_goal FOREIGN KEY (goal_id) REFERENCES career_goals (id) ON DELETE CASCADE,
    CONSTRAINT fk_career_goal_skills_skill FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE
);

-- Where the user stands on each roadmap skill. No row means NOT_STARTED.
CREATE TABLE career_goal_skill_progress (
    goal_id    UUID        NOT NULL,
    skill_id   BIGINT      NOT NULL,
    status     VARCHAR(12) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_career_goal_skill_progress PRIMARY KEY (goal_id, skill_id),
    CONSTRAINT fk_career_goal_progress_goal FOREIGN KEY (goal_id) REFERENCES career_goals (id) ON DELETE CASCADE,
    CONSTRAINT fk_career_goal_progress_skill FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE,
    CONSTRAINT ck_career_goal_progress_status CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_career_goal_skills_skill ON career_goal_skills (skill_id);
CREATE INDEX idx_career_goal_progress_skill ON career_goal_skill_progress (skill_id);
