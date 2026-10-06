-- Full name on the account, and email verification by one-time code.

ALTER TABLE users ADD COLUMN full_name VARCHAR(100);
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

-- Accounts created before verification existed are treated as verified, so nobody who could
-- sign in yesterday is locked out today.
UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL;

-- At most one live code per user: a resend replaces it. The code itself is never stored,
-- only an HMAC of it keyed by a server secret, so a database copy does not reveal codes.
CREATE TABLE email_verification_codes (
    user_id         UUID        NOT NULL,
    code_hash       VARCHAR(64) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER     NOT NULL DEFAULT 0,
    last_sent_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_email_verification_codes PRIMARY KEY (user_id),
    CONSTRAINT fk_email_verification_codes_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_verification_attempts CHECK (failed_attempts >= 0)
);
