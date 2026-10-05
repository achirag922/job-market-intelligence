-- V9.16: the in-app notification center. Notifications are derived from existing data (job-alert
-- matches, follow-ups, interview-stage applications, learning target dates, achievements); the
-- dedupe key makes each one appear once however often it is derived. Per account, removed with it.

CREATE TABLE notifications (
    id         UUID          NOT NULL,
    user_id    UUID          NOT NULL,
    type       VARCHAR(12)   NOT NULL,
    dedupe_key VARCHAR(200)  NOT NULL,
    title      VARCHAR(200)  NOT NULL,
    body       VARCHAR(500),
    link       VARCHAR(200),
    created_at TIMESTAMPTZ   NOT NULL,
    read_at    TIMESTAMPTZ,

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_notifications_dedupe UNIQUE (user_id, dedupe_key),
    CONSTRAINT ck_notifications_type CHECK (type IN ('JOB_MATCH', 'FOLLOW_UP', 'INTERVIEW', 'LEARNING', 'CAREER'))
);

CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE notification_preferences (
    user_id      UUID         NOT NULL,
    job_matches  BOOLEAN      NOT NULL DEFAULT TRUE,
    follow_ups   BOOLEAN      NOT NULL DEFAULT TRUE,
    interviews   BOOLEAN      NOT NULL DEFAULT TRUE,
    learning     BOOLEAN      NOT NULL DEFAULT TRUE,
    career       BOOLEAN      NOT NULL DEFAULT TRUE,
    -- When notifications were last derived for this account, so reads stay cheap.
    refreshed_at TIMESTAMPTZ,

    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id),
    CONSTRAINT fk_notification_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
