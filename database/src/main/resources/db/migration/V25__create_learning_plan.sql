-- V9.5: the owner's learning plan: one item per thing to learn, and the resources they add to it.
-- Items can point at the V7.4 career goal whose roadmap they serve; completing one records the
-- roadmap skill as completed there. Nothing here changes a resume.

CREATE TABLE learning_items (
    id           UUID          NOT NULL,
    user_id      UUID          NOT NULL,
    goal_id      UUID,
    skill_id     BIGINT,
    skill_name   VARCHAR(100)  NOT NULL,
    topic        VARCHAR(200)  NOT NULL,
    priority     VARCHAR(6)    NOT NULL DEFAULT 'MEDIUM',
    status       VARCHAR(12)   NOT NULL DEFAULT 'NOT_STARTED',
    progress     SMALLINT      NOT NULL DEFAULT 0,
    target_date  DATE,
    notes        VARCHAR(2000),
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    CONSTRAINT pk_learning_items PRIMARY KEY (id),
    CONSTRAINT fk_learning_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_items_goal FOREIGN KEY (goal_id) REFERENCES career_goals (id) ON DELETE SET NULL,
    CONSTRAINT fk_learning_items_skill FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE SET NULL,
    CONSTRAINT ck_learning_items_topic CHECK (length(btrim(topic)) > 0),
    CONSTRAINT ck_learning_items_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_learning_items_status CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_learning_items_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT ck_learning_items_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX idx_learning_items_user ON learning_items (user_id, created_at);
CREATE INDEX idx_learning_items_goal ON learning_items (goal_id);
CREATE INDEX idx_learning_items_skill ON learning_items (skill_id);

CREATE TABLE learning_resources (
    id         UUID          NOT NULL,
    item_id    UUID          NOT NULL,
    user_id    UUID          NOT NULL,
    title      VARCHAR(200)  NOT NULL,
    url        VARCHAR(500)  NOT NULL,
    type       VARCHAR(15)   NOT NULL,
    notes      VARCHAR(1000),
    created_at TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_learning_resources PRIMARY KEY (id),
    CONSTRAINT fk_learning_resources_item FOREIGN KEY (item_id) REFERENCES learning_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_resources_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_learning_resources_type CHECK (type IN ('COURSE', 'VIDEO', 'ARTICLE', 'DOCUMENTATION', 'PROJECT', 'OTHER')),
    CONSTRAINT ck_learning_resources_url CHECK (url ~* '^https?://')
);

CREATE INDEX idx_learning_resources_item ON learning_resources (item_id, created_at);
CREATE INDEX idx_learning_resources_user ON learning_resources (user_id);
