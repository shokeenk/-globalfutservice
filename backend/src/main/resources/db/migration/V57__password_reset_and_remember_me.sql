-- =============================================================================
--  V57: password recovery, and "Remember me" on sign-in (2026-10-21)
--
--  password_reset_token
--      One reset link asked for from "Forgot password?" or "Set a password". Only the
--      SHA-256 of the token in the link is kept, so a dump of this table opens nothing.
--      Usable once (used_at), for 30 minutes (expires_at). Asking again, or setting a
--      password some other way, voids the ones still open (revoked_at).
--
--  refresh_token.persistent
--      Whether the session was started with "Remember me" ticked. TRUE: the refresh
--      cookie has a Max-Age and outlives the browser, as every session did before this.
--      FALSE: a session cookie the browser drops when it closes. Carried down the family
--      on every refresh, so the cookie keeps the kind the customer chose.
-- =============================================================================

CREATE TABLE password_reset_token (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX password_reset_token_account_ix ON password_reset_token (account_id);

ALTER TABLE refresh_token
    ADD COLUMN persistent BOOLEAN NOT NULL DEFAULT TRUE;
