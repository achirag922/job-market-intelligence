-- V9.7: one professional portfolio per account, private until the owner publishes it.
--
-- The sections reuse the V9.4 resume builder's shapes and are stored as JSON; section visibility
-- decides what a published profile shows. The slug is the public address, /profile/{slug}.

CREATE TABLE portfolios (
    user_id      UUID          NOT NULL,
    slug         VARCHAR(50)   NOT NULL,
    display_name VARCHAR(120)  NOT NULL,
    visibility   VARCHAR(7)    NOT NULL DEFAULT 'PRIVATE',
    content      JSONB         NOT NULL,
    sections     JSONB         NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    published_at TIMESTAMPTZ,

    CONSTRAINT pk_portfolios PRIMARY KEY (user_id),
    CONSTRAINT fk_portfolios_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_portfolios_slug UNIQUE (slug),
    CONSTRAINT ck_portfolios_slug CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$'),
    CONSTRAINT ck_portfolios_visibility CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT ck_portfolios_published CHECK ((visibility = 'PUBLIC') = (published_at IS NOT NULL))
);
