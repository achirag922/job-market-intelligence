-- V6.10.4: every resume belongs to the account that uploaded it.
--
-- Nullable only because resumes uploaded before accounts existed have no owner. Those rows
-- are reachable by nobody (the API treats them like missing resumes); every new upload sets
-- the owner from the signed-in session. Removing an account removes its resumes.

ALTER TABLE resumes ADD COLUMN user_id UUID;

ALTER TABLE resumes
    ADD CONSTRAINT fk_resumes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

CREATE INDEX idx_resumes_user ON resumes (user_id);

COMMENT ON COLUMN resumes.user_id IS
    'Owning account. Set from the authenticated session on upload, never from client input.';
