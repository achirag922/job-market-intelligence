-- V6.10.1: application users, the foundation for authentication.
--
-- Holds only what signing in needs. No plain-text password is ever stored: password_hash
-- is a self-describing encoded hash, prefixed with its algorithm (e.g. "{bcrypt}$2a$12$..."),
-- so the algorithm can be strengthened later without a schema change.

CREATE TABLE users (
    id            UUID         NOT NULL,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    -- Emails are stored normalised, so "Jane@Example.com" and "jane@example.com" cannot
    -- both register: the unique constraint is effectively case-insensitive.
    CONSTRAINT ck_users_email_normalised CHECK (email = lower(btrim(email)) AND email <> ''),
    CONSTRAINT ck_users_password_hash_present CHECK (password_hash <> ''),
    CONSTRAINT ck_users_role_present CHECK (role <> '')
);

COMMENT ON COLUMN users.password_hash IS
    'Encoded one-way hash with an {algorithm} prefix. Never a plain-text password; never returned by the API.';
COMMENT ON COLUMN users.role IS
    'UserRole enum name, e.g. USER. Free text so that new roles need no migration.';
